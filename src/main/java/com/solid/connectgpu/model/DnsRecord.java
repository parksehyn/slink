package com.solid.connectgpu.model;

import java.time.Instant;
import java.util.UUID;

/**
 * 내부 DNS 레코드 한 건. 상용 DNS 콘솔(Cloudflare/Route53)의 레코드와 동일한 개념이다.
 * <ul>
 *   <li>{@code A}     — {@code name}(호스트) → {@code value}(VM 사설 IPv4)</li>
 *   <li>{@code CNAME} — {@code name}(별칭)   → {@code value}(대상 호스트 이름)</li>
 * </ul>
 * 실제 존(zone) 반영은 {@link com.solid.connectgpu.port.DnsProvider}가 담당하며 현재는 모의 구현이다.
 */
public class DnsRecord {

    private final String id;
    private final String ownerId;       // user email
    private DnsRecordType type;
    private String name;                // 호스트 이름 (예: app1.solid.internal)
    private String value;               // A: IPv4 / CNAME: 대상 호스트 이름
    private int ttl;                    // 초 단위 (Time To Live)
    private final Instant createdAt;
    private Instant updatedAt;

    public DnsRecord(String ownerId, DnsRecordType type, String name, String value, int ttl) {
        this.id = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.type = type;
        this.name = name;
        this.value = value;
        this.ttl = ttl;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId()            { return id; }
    public String getOwnerId()       { return ownerId; }
    public DnsRecordType getType()   { return type; }
    public String getName()          { return name; }
    public String getValue()         { return value; }
    public int getTtl()              { return ttl; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getUpdatedAt()    { return updatedAt; }

    public void setType(DnsRecordType type) { this.type = type; touch(); }
    public void setName(String name)        { this.name = name; touch(); }
    public void setValue(String value)      { this.value = value; touch(); }
    public void setTtl(int ttl)             { this.ttl = ttl; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }
}
