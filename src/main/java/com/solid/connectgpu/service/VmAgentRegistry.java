package com.solid.connectgpu.service;

import com.solid.connectgpu.dto.AgentHeartbeatResponse;
import com.solid.connectgpu.model.VmAgent;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class VmAgentRegistry {

    private final ConcurrentHashMap<String, VmAgent> agents = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    /**
     * Orphaned CLOSE_TUNNEL commands for deleted services that still had an active tunnel.
     * Keyed by "instanceId|ownerId" so commands are scoped to a specific student's agent
     * on a specific VM — a different student on the same VM cannot drain another's commands.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<AgentHeartbeatResponse.TunnelCommand>>
            orphanCloses = new ConcurrentHashMap<>();

    /**
     * Registers a new agent. ownerId ties the agent to a verified student account,
     * ensuring the agent can only act on services owned by that student.
     */
    public VmAgent register(String instanceId, String ownerId) {
        String token = generateToken();
        VmAgent agent = new VmAgent(instanceId, ownerId, token);
        agents.put(agent.getAgentId(), agent);
        return agent;
    }

    /**
     * Validates agent credentials and records the heartbeat timestamp.
     * Returns empty if agentId not found or token mismatch.
     */
    public Optional<VmAgent> validate(String agentId, String token) {
        VmAgent agent = agents.get(agentId);
        if (agent == null || !agent.getAgentToken().equals(token)) return Optional.empty();
        agent.updateHeartbeat();
        return Optional.of(agent);
    }

    public Optional<VmAgent> findById(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /** true if at least one agent for this instanceId sent a heartbeat within the last 60 s */
    public boolean isInstanceAlive(String instanceId) {
        return agents.values().stream()
                .anyMatch(a -> a.getInstanceId().equals(instanceId) && a.isAlive());
    }

    /**
     * Queues a CLOSE_TUNNEL command for a service that was deleted while its tunnel
     * was still active. Keyed by both instanceId and ownerId so commands are only
     * drained by an agent belonging to the same student on the same VM.
     */
    public void addOrphanClose(String instanceId, String ownerId,
                               AgentHeartbeatResponse.TunnelCommand cmd) {
        orphanCloses.computeIfAbsent(orphanKey(instanceId, ownerId),
                k -> new CopyOnWriteArrayList<>()).add(cmd);
    }

    /**
     * Returns and removes all queued orphan-close commands for the given instanceId + ownerId pair.
     * A different owner on the same instanceId will not see these commands.
     */
    public List<AgentHeartbeatResponse.TunnelCommand> drainOrphanCloses(String instanceId,
                                                                         String ownerId) {
        CopyOnWriteArrayList<AgentHeartbeatResponse.TunnelCommand> list =
                orphanCloses.remove(orphanKey(instanceId, ownerId));
        return list != null ? List.copyOf(list) : List.of();
    }

    private static String orphanKey(String instanceId, String ownerId) {
        return instanceId + "|" + ownerId;
    }

    private String generateToken() {
        byte[] bytes = new byte[16];
        rng.nextBytes(bytes);
        return "at-" + HexFormat.of().formatHex(bytes);
    }
}
