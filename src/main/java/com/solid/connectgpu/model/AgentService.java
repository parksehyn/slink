package com.solid.connectgpu.model;

import java.time.Instant;

/**
 * Agent가 보고한 서비스 하나 (unified-agent-design.md §3.2의 Service).
 *
 * <p>외부 Agent(COLAB/EXTERNAL)는 현재 서비스와 1:1이라 목록에 한 항목만 갖는다.
 * SOLID_VM Agent의 서비스는 아직 {@link ServiceEntry}(ServiceRegistry)가 관리하며
 * M2에서 이 모델로 수렴한다.
 */
public class AgentService {

    private String name;
    private final ResourceType type;
    private ResourceStatus status;        // 저장 상태: PENDING/ACTIVE/STOPPED
    private String publicUrl;
    private String serviceToken;          // jupyter token 등 (선택)
    private Instant expiresAt;            // 서비스 자체 TTL (nullable)
    private Instant updatedAt;

    public AgentService(String name, ResourceType type) {
        this.name = name;
        this.type = type;
        this.status = ResourceStatus.PENDING;
        this.updatedAt = Instant.now();
    }

    /** 영속 스냅샷으로부터 복원. */
    public AgentService(String name, ResourceType type, ResourceStatus status,
                        String publicUrl, String serviceToken, Instant expiresAt, Instant updatedAt) {
        this.name = name;
        this.type = type;
        this.status = status != null ? status : ResourceStatus.PENDING;
        this.publicUrl = publicUrl;
        this.serviceToken = serviceToken;
        this.expiresAt = expiresAt;
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * 읽기 시점 계산 상태. 저장 상태(STOPPED)와 만료·Agent liveness를 종합한다.
     * STOPPED → EXPIRED → (URL 없으면)PENDING → (heartbeat 끊겼으면)STALE → ACTIVE.
     */
    public ResourceStatus effectiveStatus(boolean agentAlive) {
        if (status == ResourceStatus.STOPPED) return ResourceStatus.STOPPED;
        if (isExpired()) return ResourceStatus.EXPIRED;
        if (publicUrl == null || publicUrl.isBlank()) return ResourceStatus.PENDING;
        if (!agentAlive) return ResourceStatus.STALE;
        return ResourceStatus.ACTIVE;
    }

    public String getName()          { return name; }
    public ResourceType getType()    { return type; }
    public ResourceStatus getStatus(){ return status; }
    public String getPublicUrl()     { return publicUrl; }
    public String getServiceToken()  { return serviceToken; }
    public Instant getExpiresAt()    { return expiresAt; }
    public Instant getUpdatedAt()    { return updatedAt; }

    public void setName(String name)       { this.name = name; touch(); }
    public void setStatus(ResourceStatus s){ this.status = s; touch(); }
    public void setPublicUrl(String url)   { this.publicUrl = url; touch(); }
    public void setServiceToken(String t)  { this.serviceToken = t; touch(); }
    public void setExpiresAt(Instant t)    { this.expiresAt = t; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }
}
