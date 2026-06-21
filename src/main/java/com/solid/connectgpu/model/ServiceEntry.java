package com.solid.connectgpu.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class ServiceEntry {

    private final String id;
    private final String ownerId;       // user email
    private String name;
    private final String instanceId;    // e.g. solid-32211690
    private final String privateIp;     // VM 사설 IP
    private final int localPort;
    private final Protocol protocol;
    private ServiceScope scope;
    private ServiceStatus status;
    // 인바운드 공개 시 접근 정책. 현재는 저장·표시만 하며 실제 차단은 미시행(예정).
    private AccessPolicy accessPolicy = AccessPolicy.DKU_INTERNAL;
    private List<String> allowedEmails = List.of();
    private String internalHostname; // name.instanceId.solid.internal — updated on name change
    private String publicUrl;
    private Instant publicExpiresAt;
    // Scope before publish(); restored on unpublish or TTL expiry. Null means not currently published.
    private ServiceScope scopeBeforePublish;
    private AgentCommand pendingCommand = AgentCommand.NONE;
    private String agentId; // agentId of the VM Agent currently managing the tunnel
    private final Instant createdAt;
    private Instant updatedAt;

    public ServiceEntry(String ownerId, String name, String instanceId, String privateIp,
                        int localPort, Protocol protocol, ServiceScope scope) {
        this.id = UUID.randomUUID().toString();
        this.ownerId = ownerId;
        this.name = name;
        this.instanceId = instanceId;
        this.privateIp = privateIp;
        this.localPort = localPort;
        this.protocol = protocol;
        this.scope = scope;
        this.status = ServiceStatus.UNKNOWN;
        this.internalHostname = name + "." + instanceId + ".solid.internal";
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isPublicExpired() {
        return publicExpiresAt != null && Instant.now().isAfter(publicExpiresAt);
    }

    public String getId()                  { return id; }
    public String getOwnerId()             { return ownerId; }
    public String getName()                { return name; }
    public String getInstanceId()          { return instanceId; }
    public String getPrivateIp()           { return privateIp; }
    public int getLocalPort()              { return localPort; }
    public Protocol getProtocol()          { return protocol; }
    public ServiceScope getScope()         { return scope; }
    public ServiceStatus getStatus()       { return status; }
    public AccessPolicy getAccessPolicy()  { return accessPolicy; }
    public List<String> getAllowedEmails() { return allowedEmails; }
    public String getInternalHostname()    { return internalHostname; }
    public String getPublicUrl()           { return publicUrl; }
    public Instant getPublicExpiresAt()    { return publicExpiresAt; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getUpdatedAt()          { return updatedAt; }

    public void setName(String name)             { this.name = name; this.internalHostname = name + "." + instanceId + ".solid.internal"; touch(); }
    public void setScope(ServiceScope scope)     { this.scope = scope; touch(); }
    public void setStatus(ServiceStatus status)  { this.status = status; touch(); }
    public void setAccessPolicy(AccessPolicy policy) { this.accessPolicy = policy; touch(); }
    public void setAllowedEmails(List<String> emails) {
        this.allowedEmails = emails == null ? List.of() : List.copyOf(emails);
        touch();
    }
    public void setPublicUrl(String publicUrl)   { this.publicUrl = publicUrl; touch(); }
    public void setPublicExpiresAt(Instant t)    { this.publicExpiresAt = t; touch(); }
    public ServiceScope getScopeBeforePublish()  { return scopeBeforePublish; }
    public void setScopeBeforePublish(ServiceScope s) { this.scopeBeforePublish = s; }
    public AgentCommand getPendingCommand()       { return pendingCommand; }
    public void setPendingCommand(AgentCommand c) { this.pendingCommand = c; touch(); }
    public String getAgentId()                   { return agentId; }
    public void setAgentId(String agentId)        { this.agentId = agentId; }

    private void touch() { this.updatedAt = Instant.now(); }
}
