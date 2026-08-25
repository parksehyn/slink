package com.solid.connectgpu.service;

import tools.jackson.databind.ObjectMapper;
import com.solid.connectgpu.dto.AgentHeartbeatResponse;
import com.solid.connectgpu.dto.ExternalResourceSnapshot;
import com.solid.connectgpu.dto.ResourceAgentRegisterRequest;
import com.solid.connectgpu.dto.ResourceReportRequest;
import com.solid.connectgpu.dto.VmAgentSnapshot;
import com.solid.connectgpu.model.Agent;
import com.solid.connectgpu.model.AgentLocation;
import com.solid.connectgpu.model.AgentService;
import com.solid.connectgpu.model.ResourceStatus;
import com.solid.connectgpu.model.ResourceType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 통합 Agent 저장소 (unified-agent-design.md M1). 기존 {@code VmAgentRegistry}(인바운드)와
 * {@code ExternalResourceRegistry}(아웃바운드)를 단일 맵으로 수렴한다 — Agent의 위치는
 * {@link AgentLocation} 속성이고, 등록/검증/heartbeat 생명주기는 위치와 무관하게 동일하다.
 *
 * <p>기존 API·토큰 접두사({@code at-}/{@code rat-})·저장 파일 포맷은 그대로 유지한다
 * (배포된 에이전트·store 파일과의 호환). 저장은 위치별 두 파일에 기존 스냅샷 포맷으로
 * 나눠 쓰며, 단일 포맷 통합은 M2 과제다.
 *
 * <p>heartbeat는 영속하지 않는다(수 초 주기로 store를 thrash). 재시작 직후 {@code lastHeartbeatAt}이
 * 잠시 낡는 것은 무해하며, 영속된 토큰이 핵심이다.
 */
@Service
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final ConcurrentHashMap<String, Agent> agents = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();
    private final ObjectMapper mapper;
    private final String vmStoreFile;        // SOLID_VM agent + orphan close 명령
    private final String externalStoreFile;  // 외부 agent(=자원)
    /** 소유자(학번)당 외부 Agent 상한. 0 이하면 무제한. */
    private final int maxExternalPerOwner;

    /**
     * Orphaned CLOSE_TUNNEL commands for deleted services that still had an active tunnel.
     * Keyed by "instanceId|ownerId" so commands are scoped to a specific student's agent
     * on a specific VM — a different student on the same VM cannot drain another's commands.
     */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<AgentHeartbeatResponse.TunnelCommand>>
            orphanCloses = new ConcurrentHashMap<>();

    public AgentRegistry(ObjectMapper mapper,
                         @Value("${agent.store.file:}") String vmStoreFile,
                         @Value("${resource.store.file:}") String externalStoreFile,
                         @Value("${resource.max-per-owner:0}") int maxExternalPerOwner) {
        this.mapper = mapper;
        this.vmStoreFile = vmStoreFile == null ? "" : vmStoreFile.trim();
        this.externalStoreFile = externalStoreFile == null ? "" : externalStoreFile.trim();
        this.maxExternalPerOwner = maxExternalPerOwner;
    }

    // ── 공통 ──────────────────────────────────────────────────────────────

    public Optional<Agent> findById(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /** 지표: Agent 수 집계 (unified-agent-design.md §8.1). location=null이면 전체. */
    public long countAgents(AgentLocation location, boolean onlineOnly) {
        return agents.values().stream()
                .filter(a -> location == null || a.getLocation() == location)
                .filter(a -> !onlineOnly || a.isAlive())
                .count();
    }

    /**
     * Agent 자격 검증 + heartbeat 갱신. 미존재/토큰 불일치/위치 불일치 시 empty.
     * 위치를 확인하는 이유: SOLID_VM용 엔드포인트에 외부 Agent 토큰이 통과하면
     * instanceId=null 경로로 새는 것을 막는다 (반대 방향도 동일).
     */
    private Optional<Agent> validate(String agentId, String token, boolean external) {
        Agent a = agents.get(agentId);
        if (a == null || token == null || !token.equals(a.getAgentToken())) return Optional.empty();
        if (a.getLocation().isExternal() != external) return Optional.empty();
        a.updateHeartbeat();
        return Optional.of(a);
    }

    /** SOLID_VM Agent 검증 (기존 VmAgentRegistry.validate 호환). */
    public Optional<Agent> validateVm(String agentId, String token) {
        return validate(agentId, token, false);
    }

    /** 외부 Agent(COLAB/EXTERNAL) 검증 (기존 ExternalResourceRegistry.validateAgent 호환). */
    public Optional<Agent> validateExternal(String agentId, String token) {
        return validate(agentId, token, true);
    }

    // ── SOLID_VM (기존 인바운드 VM Agent) ────────────────────────────────

    /**
     * SOLID_VM Agent 등록. ownerId는 검증된 학생 계정에 묶여 해당 학생의 서비스에만
     * 명령이 스코프된다.
     */
    public Agent register(String instanceId, String ownerId) {
        Agent agent = Agent.forVm(instanceId, ownerId, "at-" + randomHex());
        agents.put(agent.getAgentId(), agent);
        persistVm();
        return agent;
    }

    /** true if at least one agent for this instanceId sent a heartbeat within the liveness window */
    public boolean isInstanceAlive(String instanceId) {
        return agents.values().stream()
                .anyMatch(a -> a.getLocation() == AgentLocation.SOLID_VM
                        && instanceId.equals(a.getInstanceId()) && a.isAlive());
    }

    /** 이 소유자(학번)의 VM 중 에이전트가 살아있는 instanceId 집합 (포털 per-VM 온라인 표시용). */
    public Set<String> aliveInstanceIds(String ownerId) {
        return agents.values().stream()
                .filter(a -> a.getLocation() == AgentLocation.SOLID_VM
                        && a.getOwnerId().equals(ownerId) && a.isAlive())
                .map(Agent::getInstanceId)
                .collect(Collectors.toSet());
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
        persistVm();
    }

    /**
     * Returns and removes all queued orphan-close commands for the given instanceId + ownerId pair.
     * A different owner on the same instanceId will not see these commands.
     */
    public List<AgentHeartbeatResponse.TunnelCommand> drainOrphanCloses(String instanceId,
                                                                         String ownerId) {
        CopyOnWriteArrayList<AgentHeartbeatResponse.TunnelCommand> list =
                orphanCloses.remove(orphanKey(instanceId, ownerId));
        if (list != null) persistVm();
        return list != null ? List.copyOf(list) : List.of();
    }

    private static String orphanKey(String instanceId, String ownerId) {
        return instanceId + "|" + ownerId;
    }

    // ── COLAB/EXTERNAL (기존 아웃바운드 외부 자원) ───────────────────────

    /**
     * 등록 토큰(grant)으로 외부 Agent를 생성한다. ownerId·resourceType·name은 grant에서
     * 가져온다(클라이언트 body 불신). 동일 {@code (owner, name, type)} Agent가 있으면
     * 교체한다(새 Colab 런타임이 같은 카드를 갱신).
     */
    public Agent createFromGrant(RegistrationTokenRegistry.Grant grant,
                                 ResourceAgentRegisterRequest req) {
        String ownerId = grant.ownerId();
        ResourceType type = grant.resourceType();
        if (type == null) throw new IllegalArgumentException("resourceType is required");
        String name = (grant.name() != null && !grant.name().isBlank())
                ? grant.name().trim()
                : (req != null && req.name() != null && !req.name().isBlank() ? req.name().trim() : "resource");

        // 동일 (owner,name,type) 외부 Agent 교체
        agents.values().stream()
                .filter(a -> a.getLocation().isExternal() && a.getOwnerId().equals(ownerId))
                .filter(a -> {
                    AgentService s = a.primaryService();
                    return s != null && s.getName().equals(name) && s.getType() == type;
                })
                .map(Agent::getAgentId).toList()
                .forEach(agents::remove);

        if (maxExternalPerOwner > 0 && countExternalByOwner(ownerId) >= maxExternalPerOwner)
            throw new IllegalArgumentException("외부 자원 개수 상한(" + maxExternalPerOwner + "개)을 초과했습니다.");

        AgentService svc = new AgentService(name, type);
        AgentLocation location = type == ResourceType.COLAB_GPU ? AgentLocation.COLAB
                                                                : AgentLocation.EXTERNAL;
        Agent agent = Agent.forExternal(location, ownerId, "rat-" + randomHex(), svc);
        if (req != null && req.publicUrl() != null && !req.publicUrl().isBlank()) {
            svc.setPublicUrl(req.publicUrl().trim());
            if (req.serviceToken() != null && !req.serviceToken().isBlank())
                svc.setServiceToken(req.serviceToken().trim());
            svc.setExpiresAt(parseInstant(req.expiresAt()));
            svc.setStatus(ResourceStatus.ACTIVE);
        }
        agents.put(agent.getAgentId(), agent);
        persistExternal();
        return agent;
    }

    public List<Agent> findExternalByOwner(String ownerId) {
        return agents.values().stream()
                .filter(a -> a.getLocation().isExternal() && a.getOwnerId().equals(ownerId))
                .sorted(Comparator.comparing(Agent::getRegisteredAt).reversed())
                .toList();
    }

    public Optional<Agent> findExternalById(String id) {
        return findById(id).filter(a -> a.getLocation().isExternal());
    }

    /** 외부 Agent의 서비스 생명주기 보고 반영(컨트롤러가 validateExternal로 검증한 Agent 전달). */
    public void report(Agent agent, ResourceReportRequest req) {
        AgentService svc = agent.primaryService();
        if (svc == null) throw new IllegalArgumentException("agent has no service");
        String event = req.event() == null ? "" : req.event();
        switch (event) {
            case "RESOURCE_READY" -> {
                if (req.publicUrl() != null && !req.publicUrl().isBlank())
                    svc.setPublicUrl(req.publicUrl().trim());
                if (req.serviceToken() != null && !req.serviceToken().isBlank())
                    svc.setServiceToken(req.serviceToken().trim());
                Instant exp = parseInstant(req.expiresAt());
                if (exp != null) svc.setExpiresAt(exp);
                svc.setStatus(ResourceStatus.ACTIVE);
            }
            case "RESOURCE_STOPPED" -> { svc.setStatus(ResourceStatus.STOPPED); svc.setPublicUrl(null); }
            case "RESOURCE_FAILED" -> {
                svc.setStatus(ResourceStatus.STOPPED);
                log.warn("[AGENT] external agent reported FAILED for {}: {}", agent.getAgentId(), req.reason());
            }
            default -> throw new IllegalArgumentException("Unknown event type: " + req.event());
        }
        persistExternal();
    }

    public boolean deleteExternal(String id, String ownerId) {
        Agent a = agents.get(id);
        if (a == null || !a.getLocation().isExternal() || !a.getOwnerId().equals(ownerId)) return false;
        agents.remove(id);
        persistExternal();
        return true;
    }

    /**
     * 만료 외부 Agent 회수 — 서비스 {@code expiresAt}이 지난 것을 삭제한다. 전역 유일 정책의
     * 자원 풀이 졸업·방치 항목으로 비대해지는 것을 막는다(인바운드 cleanExpiredPublic 주기와 동일).
     */
    @Scheduled(fixedDelay = 3_600_000) // 1시간마다
    public void cleanExpired() {
        List<Agent> expired = agents.values().stream()
                .filter(a -> a.getLocation().isExternal())
                .filter(a -> a.primaryService() != null && a.primaryService().isExpired())
                .toList();
        for (Agent a : expired) {
            agents.remove(a.getAgentId());
            log.info("[AGENT] reclaimed expired external agent {} (owner={})",
                    a.primaryService().getName(), a.getOwnerId());
        }
        if (!expired.isEmpty()) persistExternal();
    }

    private int countExternalByOwner(String ownerId) {
        return (int) agents.values().stream()
                .filter(a -> a.getLocation().isExternal() && a.getOwnerId().equals(ownerId))
                .count();
    }

    // ── 영속화 (기존 두 store 파일·포맷 유지 — 배포 호환. 단일 포맷은 M2) ──

    @PostConstruct
    public void load() {
        loadVm();
        loadExternal();
    }

    private void loadVm() {
        if (vmStoreFile.isEmpty() || !Files.exists(Path.of(vmStoreFile))) return;
        try {
            VmAgentSnapshot.Store store = mapper.readValue(
                    Files.readAllBytes(Path.of(vmStoreFile)), VmAgentSnapshot.Store.class);
            if (store.agents() != null) {
                for (VmAgentSnapshot s : store.agents()) {
                    Agent a = new Agent(s.agentId(), s.ownerId(), AgentLocation.SOLID_VM,
                            s.instanceId(), s.agentToken(),
                            Instant.parse(s.lastHeartbeatAt()), Instant.parse(s.registeredAt()));
                    agents.put(a.getAgentId(), a);
                }
            }
            if (store.orphans() != null) {
                for (VmAgentSnapshot.OrphanCloseSnapshot o : store.orphans()) {
                    orphanCloses.put(o.key(), new CopyOnWriteArrayList<>(o.commands()));
                }
            }
            log.info("[AGENT] loaded {} vm agents from {}", store.agents() == null ? 0 : store.agents().size(), vmStoreFile);
        } catch (Exception e) {
            log.warn("[AGENT] failed to load vm store {}: {}", vmStoreFile, e.getMessage());
        }
    }

    private void loadExternal() {
        if (externalStoreFile.isEmpty() || !Files.exists(Path.of(externalStoreFile))) return;
        try {
            ExternalResourceSnapshot[] snaps = mapper.readValue(
                    Files.readAllBytes(Path.of(externalStoreFile)), ExternalResourceSnapshot[].class);
            int count = 0;
            for (ExternalResourceSnapshot s : snaps) {
                ResourceType type = ResourceType.valueOf(s.resourceType());
                AgentService svc = new AgentService(s.name(), type,
                        ResourceStatus.valueOf(s.status()), s.publicUrl(), s.serviceToken(),
                        parseInstant(s.expiresAt()), Instant.parse(s.updatedAt()));
                AgentLocation location = type == ResourceType.COLAB_GPU ? AgentLocation.COLAB
                                                                        : AgentLocation.EXTERNAL;
                Agent a = new Agent(s.id(), s.ownerId(), location, null, s.agentToken(),
                        parseInstant(s.lastHeartbeatAt()), Instant.parse(s.createdAt()));
                a.getServices().add(svc);
                agents.put(a.getAgentId(), a);
                count++;
            }
            log.info("[AGENT] loaded {} external agents from {}", count, externalStoreFile);
        } catch (Exception e) {
            log.warn("[AGENT] failed to load external store {}: {}", externalStoreFile, e.getMessage());
        }
    }

    private void persistVm() {
        if (vmStoreFile.isEmpty()) return;
        try {
            List<VmAgentSnapshot> agentSnaps = agents.values().stream()
                    .filter(a -> a.getLocation() == AgentLocation.SOLID_VM)
                    .map(a -> new VmAgentSnapshot(a.getAgentId(), a.getInstanceId(), a.getOwnerId(),
                            a.getAgentToken(), a.getLastHeartbeatAt().toString(),
                            a.getRegisteredAt().toString()))
                    .toList();
            List<VmAgentSnapshot.OrphanCloseSnapshot> orphanSnaps = orphanCloses.entrySet().stream()
                    .map(e -> new VmAgentSnapshot.OrphanCloseSnapshot(e.getKey(), List.copyOf(e.getValue())))
                    .toList();
            writeAtomic(Path.of(vmStoreFile),
                    mapper.writeValueAsBytes(new VmAgentSnapshot.Store(agentSnaps, orphanSnaps)));
        } catch (Exception e) {
            log.warn("[AGENT] failed to persist vm store {}: {}", vmStoreFile, e.getMessage());
        }
    }

    private void persistExternal() {
        if (externalStoreFile.isEmpty()) return;
        try {
            List<ExternalResourceSnapshot> snaps = agents.values().stream()
                    .filter(a -> a.getLocation().isExternal())
                    .map(a -> {
                        AgentService s = a.primaryService();
                        return new ExternalResourceSnapshot(a.getAgentId(), a.getOwnerId(),
                                s.getType().name(), s.getName(), s.getStatus().name(),
                                s.getPublicUrl(), s.getServiceToken(),
                                s.getExpiresAt() != null ? s.getExpiresAt().toString() : null,
                                a.getAgentToken(),
                                a.getLastHeartbeatAt() != null ? a.getLastHeartbeatAt().toString() : null,
                                a.getRegisteredAt().toString(), s.getUpdatedAt().toString());
                    })
                    .toList();
            writeAtomic(Path.of(externalStoreFile), mapper.writeValueAsBytes(snaps));
        } catch (Exception e) {
            log.warn("[AGENT] failed to persist external store {}: {}", externalStoreFile, e.getMessage());
        }
    }

    private static void writeAtomic(Path file, byte[] bytes) throws Exception {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private String randomHex() {
        byte[] bytes = new byte[16];
        rng.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s.trim()); } catch (Exception e) { return null; }
    }
}
