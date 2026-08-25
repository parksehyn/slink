package com.solid.connectgpu.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 내부 DNS 레코드 한 건. 상용 DNS 콘솔(Cloudflare/Route53)의 레코드와 동일한 개념이다.
 * <ul>
 *   <li>{@code A}     — {@code name}(짧은 호스트) → {@code value}(VM 사설 IPv4)</li>
 *   <li>{@code CNAME} — {@code name}(별칭)        → {@code value}(대상 호스트 이름)</li>
 * </ul>
 * {@code name}은 존 기준 짧은 라벨(예: {@code web}, 루트는 {@code @})을 저장하고
 * {@link #getFqdn()}이 {@code name + ".solid.internal"}을 계산한다.
 * A 레코드는 {@code vmId}로 만들며 값(사설 IP)은 서버가 CloudStack에서 채운다.
 * 실제 존 반영은 {@link com.solid.connectgpu.port.DnsProvider}가 담당한다.
 */
public class DnsRecord {

    public static final String ZONE = "solid.internal";

    private final String id;
    private final String ownerId;       // CloudStack account (학번)
    private DnsRecordType type;
    private String name;                // 짧은 호스트 라벨 (예: web, 루트는 @)
    private String value;               // A: IPv4 / CNAME: 대상 호스트 이름
    private int ttl;                    // 초 단위
    private String vmId;                // A 레코드의 출처 VM instanceId (CNAME은 null)
    private String vmName;              // 표시용 VM 이름
    private DnsRecordStatus status;
    private final Instant createdAt;
    private Instant updatedAt;

    /** 신규 레코드 생성 (id·시각 자동, 상태 PENDING_SYNC). */
    public DnsRecord(String ownerId, DnsRecordType type, String name, String value, int ttl,
                     String vmId, String vmName) {
        this(UUID.randomUUID().toString(), ownerId, type, name, value, ttl, vmId, vmName,
                DnsRecordStatus.PENDING_SYNC, Instant.now(), Instant.now());
    }

    /** 영속 스냅샷으로부터 복원. */
    public DnsRecord(String id, String ownerId, DnsRecordType type, String name, String value, int ttl,
                     String vmId, String vmName, DnsRecordStatus status,
                     Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.type = type;
        this.name = name;
        this.value = value;
        this.ttl = ttl;
        this.vmId = vmId;
        this.vmName = vmName;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId()              { return id; }
    public String getOwnerId()         { return ownerId; }
    public DnsRecordType getType()     { return type; }
    public String getName()            { return name; }
    public String getValue()           { return value; }
    public int getTtl()                { return ttl; }
    public String getVmId()            { return vmId; }
    public String getVmName()          { return vmName; }
    public DnsRecordStatus getStatus() { return status; }
    public Instant getCreatedAt()      { return createdAt; }
    public Instant getUpdatedAt()      { return updatedAt; }

    /** 존 기준 짧은 라벨을 FQDN으로 계산한다 (루트 {@code @}/빈값은 존 자체). */
    public String getFqdn() {
        if (name == null || name.isBlank() || name.equals("@")) return ZONE;
        return name + "." + ZONE;
    }

    public void setType(DnsRecordType type) { this.type = type; touch(); }
    public void setName(String name)        { this.name = name; touch(); }
    public void setValue(String value)      { this.value = value; touch(); }
    public void setTtl(int ttl)             { this.ttl = ttl; touch(); }
    public void setVm(String vmId, String vmName) { this.vmId = vmId; this.vmName = vmName; touch(); }
    public void setStatus(DnsRecordStatus status) { this.status = status; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }
}
