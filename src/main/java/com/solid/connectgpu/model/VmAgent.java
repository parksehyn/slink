package com.solid.connectgpu.model;

import java.time.Instant;
import java.util.UUID;

public class VmAgent {

    private final String agentId;
    private final String instanceId;
    private final String ownerId;    // user email — ties agent to a specific student account
    private final String agentToken;
    private Instant lastHeartbeatAt;
    private final Instant registeredAt;

    public VmAgent(String instanceId, String ownerId, String agentToken) {
        this.agentId = UUID.randomUUID().toString();
        this.instanceId = instanceId;
        this.ownerId = ownerId;
        this.agentToken = agentToken;
        this.registeredAt = Instant.now();
        this.lastHeartbeatAt = Instant.now();
    }

    /** 영속 스냅샷으로부터 복원 (agentId·토큰·시각 보존). */
    public VmAgent(String agentId, String instanceId, String ownerId, String agentToken,
                   Instant lastHeartbeatAt, Instant registeredAt) {
        this.agentId = agentId;
        this.instanceId = instanceId;
        this.ownerId = ownerId;
        this.agentToken = agentToken;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.registeredAt = registeredAt;
    }

    /** true if heartbeat arrived within the last 60 seconds */
    public boolean isAlive() {
        return lastHeartbeatAt != null &&
               Instant.now().isBefore(lastHeartbeatAt.plusSeconds(60));
    }

    public String getAgentId()          { return agentId; }
    public String getInstanceId()       { return instanceId; }
    public String getOwnerId()          { return ownerId; }
    public String getAgentToken()       { return agentToken; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public Instant getRegisteredAt()    { return registeredAt; }
    public void updateHeartbeat()       { this.lastHeartbeatAt = Instant.now(); }
}
