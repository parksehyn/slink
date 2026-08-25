package com.solid.connectgpu.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 통합 Agent 모델 (unified-agent-design.md §3.2). 기존 {@code VmAgent}(인바운드)와
 * {@code ExternalResource}(아웃바운드)를 하나로 수렴한다 — 위치는 {@link AgentLocation}
 * 속성이지 별개 기능이 아니다.
 *
 * <ul>
 *   <li>SOLID_VM: instanceId를 가지며 서비스는 아직 ServiceRegistry가 관리(M2에서 수렴).
 *       토큰 접두사 {@code at-} 유지.</li>
 *   <li>COLAB/EXTERNAL: 서비스와 1:1이라 {@link #services}에 한 항목.
 *       토큰 접두사 {@code rat-} 유지. Agent가 자기 터널 URL의 source of truth.</li>
 * </ul>
 */
public class Agent {

    /** liveness 윈도우(초). 10초 heartbeat 주기 기준. */
    public static final long LIVENESS_SECONDS = 60;

    private final String agentId;
    private final String ownerId;            // CloudStack account(학번)
    private final AgentLocation location;
    private final String instanceId;         // SOLID_VM 전용 (그 외 null)
    private final String agentToken;         // at-(SOLID_VM) / rat-(외부) — 기존 접두사 호환
    private Instant lastHeartbeatAt;
    private final Instant registeredAt;
    private final List<AgentService> services = new ArrayList<>();

    /** 신규 SOLID_VM Agent. */
    public static Agent forVm(String instanceId, String ownerId, String agentToken) {
        return new Agent(UUID.randomUUID().toString(), ownerId, AgentLocation.SOLID_VM,
                instanceId, agentToken, Instant.now(), Instant.now());
    }

    /** 신규 외부 Agent (서비스 1개 내장). */
    public static Agent forExternal(AgentLocation location, String ownerId, String agentToken,
                                    AgentService service) {
        Agent a = new Agent(UUID.randomUUID().toString(), ownerId, location,
                null, agentToken, Instant.now(), Instant.now());
        a.services.add(service);
        return a;
    }

    /** 영속 스냅샷으로부터 복원 (id·토큰·시각 보존). */
    public Agent(String agentId, String ownerId, AgentLocation location, String instanceId,
                 String agentToken, Instant lastHeartbeatAt, Instant registeredAt) {
        this.agentId = agentId;
        this.ownerId = ownerId;
        this.location = location;
        this.instanceId = instanceId;
        this.agentToken = agentToken;
        this.lastHeartbeatAt = lastHeartbeatAt;
        this.registeredAt = registeredAt;
    }

    /** heartbeat가 liveness 윈도우 내에 도착했는가. */
    public boolean isAlive() {
        return lastHeartbeatAt != null
                && Instant.now().isBefore(lastHeartbeatAt.plusSeconds(LIVENESS_SECONDS));
    }

    /**
     * 외부 Agent의 유일한 서비스. 자원=Agent 1:1 모델(기존 ExternalResource 호환)의 접근자.
     * SOLID_VM Agent에는 없다(M2 전까지 ServiceRegistry 관리).
     */
    public AgentService primaryService() {
        return services.isEmpty() ? null : services.get(0);
    }

    public String getAgentId()           { return agentId; }
    public String getOwnerId()           { return ownerId; }
    public AgentLocation getLocation()   { return location; }
    public String getInstanceId()        { return instanceId; }
    public String getAgentToken()        { return agentToken; }
    public Instant getLastHeartbeatAt()  { return lastHeartbeatAt; }
    public Instant getRegisteredAt()     { return registeredAt; }
    public List<AgentService> getServices() { return services; }

    public void updateHeartbeat() { this.lastHeartbeatAt = Instant.now(); }
}
