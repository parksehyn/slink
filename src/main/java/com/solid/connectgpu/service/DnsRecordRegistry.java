package com.solid.connectgpu.service;

import tools.jackson.databind.ObjectMapper;
import com.solid.connectgpu.dto.CreateDnsRecordRequest;
import com.solid.connectgpu.dto.DnsRecordSnapshot;
import com.solid.connectgpu.dto.UpdateDnsRecordRequest;
import com.solid.connectgpu.model.DnsRecord;
import com.solid.connectgpu.model.DnsRecordStatus;
import com.solid.connectgpu.model.DnsRecordType;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.model.VmInfo;
import com.solid.connectgpu.port.CloudStackProvider;
import com.solid.connectgpu.port.DnsProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 사용자별 내부 DNS 레코드(A/CNAME) 저장소. 소유자(=CloudStack 학번)로 격리한다.
 * <ul>
 *   <li>A 레코드는 {@code vmId}로 만들며, 서버가 {@link CloudStackProvider}로 소유권·사설 IP를 검증한다(명세서 §5.2/§7.3).</li>
 *   <li>변경 시 {@link DnsProvider}로 CoreDNS에 반영(상태 PENDING_SYNC→ACTIVE/FAILED).</li>
 *   <li>{@code dns.store.file} 설정 시 JSON 파일로 영속(기동 시 로드, 변경 시 저장).</li>
 * </ul>
 */
@Service
public class DnsRecordRegistry {

    private static final Logger log = LoggerFactory.getLogger(DnsRecordRegistry.class);

    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private static final int DEFAULT_TTL = 3600;
    private static final int MIN_TTL = 30;
    private static final int MAX_TTL = 86_400;

    private final ConcurrentHashMap<String, DnsRecord> records = new ConcurrentHashMap<>();
    private final DnsProvider dns;
    private final CloudStackProvider cloudStack;
    private final ObjectMapper mapper;
    private final String storeFile;

    public DnsRecordRegistry(DnsProvider dns, CloudStackProvider cloudStack, ObjectMapper mapper,
                             @Value("${dns.store.file:}") String storeFile) {
        this.dns = dns;
        this.cloudStack = cloudStack;
        this.mapper = mapper;
        this.storeFile = storeFile == null ? "" : storeFile.trim();
    }

    @PostConstruct
    void load() {
        if (storeFile.isEmpty() || !Files.exists(Path.of(storeFile))) return;
        try {
            DnsRecordSnapshot[] snaps = mapper.readValue(Files.readAllBytes(Path.of(storeFile)),
                    DnsRecordSnapshot[].class);
            for (DnsRecordSnapshot s : snaps) {
                DnsRecord r = new DnsRecord(s.id(), s.ownerId(), DnsRecordType.valueOf(s.type()),
                        s.name(), s.value(), s.ttl(), s.vmId(), s.vmName(),
                        DnsRecordStatus.valueOf(s.status()),
                        Instant.parse(s.createdAt()), Instant.parse(s.updatedAt()));
                records.put(r.getId(), r);
                // 기동 시 DNS provider(zone) 재구성
                try { dns.createRecord(r.getName(), r.getValue()); } catch (Exception ignore) {}
            }
            log.info("[DNS] loaded {} records from {}", records.size(), storeFile);
        } catch (Exception e) {
            log.warn("[DNS] failed to load store {}: {}", storeFile, e.getMessage());
        }
    }

    public List<DnsRecord> findByOwner(String ownerId) {
        return records.values().stream()
                .filter(r -> r.getOwnerId().equals(ownerId))
                .sorted(Comparator.comparing(DnsRecord::getCreatedAt).reversed())
                .toList();
    }

    public Optional<DnsRecord> findById(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public DnsRecord create(SolidIdentity identity, CreateDnsRecordRequest req) {
        String ownerId = identity.account();
        if (req.type() == null)
            throw new DnsApiException("INVALID_REQUEST", "레코드 타입(A 또는 CNAME)이 필요합니다.", 400);
        String name = normalize(req.name());
        validateName(name);

        String vmId = null, vmName = null, value;
        if (req.type() == DnsRecordType.A) {
            if (req.vmId() == null || req.vmId().isBlank())
                throw new DnsApiException("INVALID_REQUEST", "A 레코드는 vmId가 필요합니다.", 400);
            VmInfo vm = cloudStack.findVm(identity, req.vmId().trim())
                    .orElseThrow(() -> new DnsApiException("VM_NOT_FOUND",
                            "VM을 찾을 수 없거나 접근 권한이 없습니다: " + req.vmId(), 404));
            if (vm.account() != null && identity.account() != null
                    && !vm.account().equals(identity.account()))
                throw new DnsApiException("VM_NOT_OWNED", "해당 VM의 소유자가 아닙니다.", 403);
            value = vm.privateIp();
            validatePrivateIp(value);
            vmId = vm.instanceId();
            vmName = vm.displayName();
        } else { // CNAME
            value = req.value() == null ? "" : req.value().trim();
            if (value.isBlank())
                throw new DnsApiException("INVALID_REQUEST", "CNAME 대상 호스트가 필요합니다.", 400);
            if (!HOSTNAME.matcher(value).matches())
                throw new DnsApiException("INVALID_DNS_NAME", "CNAME 대상이 올바른 호스트가 아닙니다: " + value, 400);
        }

        if (nameTaken(ownerId, name, null))
            throw new DnsApiException("DUPLICATE_RECORD", "이미 존재하는 DNS 이름: " + name, 409);

        int ttl = clampTtl(req.ttl() != null ? req.ttl() : DEFAULT_TTL);
        DnsRecord record = new DnsRecord(ownerId, req.type(), name, value, ttl, vmId, vmName);
        records.put(record.getId(), record);
        persist();
        syncToDns(record, () -> dns.createRecord(record.getName(), record.getValue()));
        return record;
    }

    public Optional<DnsRecord> update(String id, String ownerId, UpdateDnsRecordRequest req) {
        DnsRecord record = records.get(id);
        if (record == null || !record.getOwnerId().equals(ownerId)) return Optional.empty();

        DnsRecordType newType = req.type() != null ? req.type() : record.getType();
        String newName = req.name() != null ? normalize(req.name()) : record.getName();
        String newValue = req.value() != null ? req.value().trim() : record.getValue();
        validateName(newName);
        validateValue(newType, newValue);
        if (nameTaken(ownerId, newName, id))
            throw new DnsApiException("DUPLICATE_RECORD", "이미 존재하는 DNS 이름: " + newName, 409);

        String oldName = record.getName();
        record.setType(newType);
        record.setName(newName);
        record.setValue(newValue);
        if (req.ttl() != null) record.setTtl(clampTtl(req.ttl()));
        record.setStatus(DnsRecordStatus.PENDING_SYNC);
        persist();

        if (!oldName.equals(newName)) {
            syncToDns(record, () -> {
                dns.deleteRecord(oldName);
                dns.createRecord(newName, newValue);
            });
        } else {
            syncToDns(record, () -> dns.updateRecord(newName, newValue));
        }
        return Optional.of(record);
    }

    public boolean delete(String id, String ownerId) {
        DnsRecord record = records.get(id);
        if (record == null || !record.getOwnerId().equals(ownerId)) return false;
        records.remove(id);
        persist();
        try { dns.deleteRecord(record.getName()); } catch (Exception e) {
            log.warn("[DNS] deleteRecord sync failed for {}: {}", record.getName(), e.getMessage());
        }
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void syncToDns(DnsRecord record, Runnable op) {
        try {
            op.run();
            record.setStatus(DnsRecordStatus.ACTIVE);
            persist();
        } catch (Exception e) {
            record.setStatus(DnsRecordStatus.FAILED);
            persist();
            throw new DnsApiException("DNS_SYNC_FAILED", "CoreDNS 반영 실패: " + e.getMessage(), 500);
        }
    }

    private void persist() {
        if (storeFile.isEmpty()) return;
        try {
            List<DnsRecordSnapshot> snaps = records.values().stream().map(r -> new DnsRecordSnapshot(
                    r.getId(), r.getOwnerId(), r.getType().name(), r.getName(), r.getValue(), r.getTtl(),
                    r.getVmId(), r.getVmName(), r.getStatus().name(),
                    r.getCreatedAt().toString(), r.getUpdatedAt().toString())).toList();
            Path file = Path.of(storeFile);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(snaps));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("[DNS] failed to persist store {}: {}", storeFile, e.getMessage());
        }
    }

    private boolean nameTaken(String ownerId, String name, String excludeId) {
        return records.values().stream()
                .anyMatch(r -> r.getOwnerId().equals(ownerId)
                        && !r.getId().equals(excludeId == null ? "" : excludeId)
                        && r.getName().equals(name));
    }

    private int clampTtl(int ttl) {
        return Math.min(Math.max(ttl, MIN_TTL), MAX_TTL);
    }

    /** 입력에서 트레일링 존(.solid.internal)을 제거하고 짧은 라벨로 정규화. 빈값/루트는 {@code @}. */
    private String normalize(String s) {
        String h = s == null ? "" : s.trim().toLowerCase().replaceAll("\\.$", "");
        if (h.isEmpty() || h.equals("@") || h.equals(DnsRecord.ZONE)) return "@";
        if (h.endsWith("." + DnsRecord.ZONE)) return h.substring(0, h.length() - DnsRecord.ZONE.length() - 1);
        return h;
    }

    private void validateName(String name) {
        if (name.equals("@")) return; // 존 루트 허용
        if (name.isBlank())
            throw new DnsApiException("INVALID_DNS_NAME", "레코드 이름이 필요합니다.", 400);
        if (!HOSTNAME.matcher(name).matches())
            throw new DnsApiException("INVALID_DNS_NAME", "DNS 이름은 소문자/숫자/하이픈만 사용할 수 있습니다: " + name, 400);
    }

    private void validateValue(DnsRecordType type, String value) {
        if (value == null || value.isBlank())
            throw new DnsApiException("INVALID_REQUEST", "레코드 값이 필요합니다.", 400);
        switch (type) {
            case A -> validatePrivateIp(value);
            case CNAME -> {
                if (!HOSTNAME.matcher(value).matches())
                    throw new DnsApiException("INVALID_DNS_NAME", "CNAME 대상이 올바른 호스트가 아닙니다: " + value, 400);
            }
        }
    }

    /** SOLID 사설 대역(10.0.0.0/8)만 허용. 루프백·링크로컬·메타데이터·공인 IP 거부 (명세서 §7.2). */
    private void validatePrivateIp(String ip) {
        if (ip == null || !IPV4.matcher(ip).matches())
            throw new DnsApiException("INVALID_IP_RANGE", "올바른 IPv4 주소가 아닙니다: " + ip, 400);
        int first = Integer.parseInt(ip.substring(0, ip.indexOf('.')));
        if (first != 10)
            throw new DnsApiException("INVALID_IP_RANGE",
                    "SOLID 사설망(10.0.0.0/8)만 등록할 수 있습니다: " + ip, 400);
    }
}
