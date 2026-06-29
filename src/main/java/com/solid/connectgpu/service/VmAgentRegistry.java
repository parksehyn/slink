package com.solid.connectgpu.service;

import tools.jackson.databind.ObjectMapper;
import com.solid.connectgpu.dto.AgentHeartbeatResponse;
import com.solid.connectgpu.dto.VmAgentSnapshot;
import com.solid.connectgpu.model.VmAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class VmAgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(VmAgentRegistry.class);

    private final ConcurrentHashMap<String, VmAgent> agents = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final ObjectMapper mapper;
    private final String storeFile;

    /**
     * Orphaned CLOSE_TUNNEL commands for deleted services that still had an active tunnel.
     * Keyed by "instanceId|ownerId" so commands are scoped to a specific student's agent
     * on a specific VM — a different student on the same VM cannot drain another's commands.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<AgentHeartbeatResponse.TunnelCommand>>
            orphanCloses = new ConcurrentHashMap<>();

    public VmAgentRegistry(ObjectMapper mapper,
                           @Value("${agent.store.file:}") String storeFile) {
        this.mapper = mapper;
        this.storeFile = storeFile == null ? "" : storeFile.trim();
    }

    @PostConstruct
    public void load() {
        if (storeFile.isEmpty() || !Files.exists(Path.of(storeFile))) return;
        try {
            VmAgentSnapshot.Store store = mapper.readValue(
                    Files.readAllBytes(Path.of(storeFile)), VmAgentSnapshot.Store.class);
            if (store.agents() != null) {
                for (VmAgentSnapshot s : store.agents()) {
                    VmAgent a = new VmAgent(s.agentId(), s.instanceId(), s.ownerId(), s.agentToken(),
                            Instant.parse(s.lastHeartbeatAt()), Instant.parse(s.registeredAt()));
                    agents.put(a.getAgentId(), a);
                }
            }
            if (store.orphans() != null) {
                for (VmAgentSnapshot.OrphanCloseSnapshot o : store.orphans()) {
                    orphanCloses.put(o.key(), new CopyOnWriteArrayList<>(o.commands()));
                }
            }
            log.info("[AGENT] loaded {} agents from {}", agents.size(), storeFile);
        } catch (Exception e) {
            log.warn("[AGENT] failed to load store {}: {}", storeFile, e.getMessage());
        }
    }

    /**
     * Registers a new agent. ownerId ties the agent to a verified student account,
     * ensuring the agent can only act on services owned by that student.
     */
    public VmAgent register(String instanceId, String ownerId) {
        String token = generateToken();
        VmAgent agent = new VmAgent(instanceId, ownerId, token);
        agents.put(agent.getAgentId(), agent);
        persist();
        return agent;
    }

    /**
     * Validates agent credentials and records the heartbeat timestamp.
     * Returns empty if agentId not found or token mismatch.
     * <p>Heartbeats are intentionally NOT persisted (they arrive every few seconds — persisting
     * each would thrash the store). After a restart {@code lastHeartbeatAt} is stale until the
     * next heartbeat (~seconds), which is harmless; the persisted {@code at-} token is what matters.
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
        persist();
    }

    /**
     * Returns and removes all queued orphan-close commands for the given instanceId + ownerId pair.
     * A different owner on the same instanceId will not see these commands.
     */
    public List<AgentHeartbeatResponse.TunnelCommand> drainOrphanCloses(String instanceId,
                                                                         String ownerId) {
        CopyOnWriteArrayList<AgentHeartbeatResponse.TunnelCommand> list =
                orphanCloses.remove(orphanKey(instanceId, ownerId));
        if (list != null) persist();
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

    private void persist() {
        if (storeFile.isEmpty()) return;
        try {
            List<VmAgentSnapshot> agentSnaps = agents.values().stream()
                    .map(a -> new VmAgentSnapshot(a.getAgentId(), a.getInstanceId(), a.getOwnerId(),
                            a.getAgentToken(), a.getLastHeartbeatAt().toString(),
                            a.getRegisteredAt().toString()))
                    .toList();
            List<VmAgentSnapshot.OrphanCloseSnapshot> orphanSnaps = orphanCloses.entrySet().stream()
                    .map(e -> new VmAgentSnapshot.OrphanCloseSnapshot(e.getKey(), List.copyOf(e.getValue())))
                    .toList();
            VmAgentSnapshot.Store store = new VmAgentSnapshot.Store(agentSnaps, orphanSnaps);
            Path file = Path.of(storeFile);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(store));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("[AGENT] failed to persist store {}: {}", storeFile, e.getMessage());
        }
    }
}
