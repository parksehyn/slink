package com.solid.connectgpu.service;

import com.solid.connectgpu.dto.CreateDnsRecordRequest;
import com.solid.connectgpu.dto.UpdateDnsRecordRequest;
import com.solid.connectgpu.model.DnsRecord;
import com.solid.connectgpu.model.DnsRecordType;
import com.solid.connectgpu.port.DnsProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 사용자별 내부 DNS 레코드(A/CNAME) 저장소. 인메모리이며 소유자 단위로 격리한다.
 * 생성·수정·삭제 시 {@link DnsProvider}(현재 {@code MockDnsProvider})를 호출하여
 * "DNS 연동은 인터페이스로 분리, 실제 반영은 모의 구현" 원칙을 유지한다.
 */
@Service
public class DnsRecordRegistry {

    // 호스트 이름: 점으로 구분된 라벨, 라벨은 소문자/숫자/하이픈
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    // IPv4
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private static final int DEFAULT_TTL = 3600;
    private static final int MIN_TTL = 30;
    private static final int MAX_TTL = 86_400;

    private final ConcurrentHashMap<String, DnsRecord> records = new ConcurrentHashMap<>();
    private final DnsProvider dns;

    public DnsRecordRegistry(DnsProvider dns) {
        this.dns = dns;
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

    public DnsRecord create(String ownerId, CreateDnsRecordRequest req) {
        if (req.type() == null) throw new IllegalArgumentException("Record type is required (A or CNAME)");
        String name = normalize(req.name());
        String value = req.value() == null ? "" : req.value().trim();
        validateName(name);
        validateValue(req.type(), value);
        if (nameTaken(ownerId, name, null))
            throw new IllegalArgumentException("DNS name already in use: " + name);

        int ttl = clampTtl(req.ttl() != null ? req.ttl() : DEFAULT_TTL);
        DnsRecord record = new DnsRecord(ownerId, req.type(), name, value, ttl);
        records.put(record.getId(), record);
        dns.createRecord(record.getName(), record.getValue());
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
            throw new IllegalArgumentException("DNS name already in use: " + newName);

        String oldName = record.getName();
        record.setType(newType);
        record.setName(newName);
        record.setValue(newValue);
        if (req.ttl() != null) record.setTtl(clampTtl(req.ttl()));

        if (!oldName.equals(newName)) {
            dns.deleteRecord(oldName);
            dns.createRecord(newName, newValue);
        } else {
            dns.updateRecord(newName, newValue);
        }
        return Optional.of(record);
    }

    public boolean delete(String id, String ownerId) {
        DnsRecord record = records.get(id);
        if (record == null || !record.getOwnerId().equals(ownerId)) return false;
        records.remove(id);
        dns.deleteRecord(record.getName());
        return true;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private boolean nameTaken(String ownerId, String name, String excludeId) {
        return records.values().stream()
                .anyMatch(r -> r.getOwnerId().equals(ownerId)
                        && !r.getId().equals(excludeId == null ? "" : excludeId)
                        && r.getName().equals(name));
    }

    private int clampTtl(int ttl) {
        return Math.min(Math.max(ttl, MIN_TTL), MAX_TTL);
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private void validateName(String name) {
        if (name.isBlank())
            throw new IllegalArgumentException("Record name is required");
        if (!HOSTNAME.matcher(name).matches())
            throw new IllegalArgumentException("Invalid host name: " + name);
    }

    private void validateValue(DnsRecordType type, String value) {
        if (value.isBlank())
            throw new IllegalArgumentException("Record value is required");
        switch (type) {
            case A -> {
                if (!IPV4.matcher(value).matches())
                    throw new IllegalArgumentException("A record value must be an IPv4 address: " + value);
            }
            case CNAME -> {
                if (!HOSTNAME.matcher(value).matches())
                    throw new IllegalArgumentException("CNAME value must be a host name: " + value);
            }
        }
    }
}
