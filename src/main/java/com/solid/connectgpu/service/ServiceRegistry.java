package com.solid.connectgpu.service;

import tools.jackson.databind.ObjectMapper;
import com.solid.connectgpu.dto.AgentHeartbeatResponse;
import com.solid.connectgpu.dto.CreateServiceRequest;
import com.solid.connectgpu.dto.ServiceEntrySnapshot;
import com.solid.connectgpu.dto.UpdateServiceRequest;
import com.solid.connectgpu.model.*;
import com.solid.connectgpu.port.CloudStackProvider;
import com.solid.connectgpu.port.DnsProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServiceRegistry.class);

    private static final int MAX_TTL_HOURS = 24;
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$|^[a-z0-9]$");

    private final ConcurrentHashMap<String, ServiceEntry> services = new ConcurrentHashMap<>();
    private final DnsProvider dns;
    private final AgentRegistry agentRegistry;
    private final CloudStackProvider cloudStack;
    private final ObjectMapper mapper;
    private final String storeFile;
    /** 소유자(학번) 1인당 서비스 개수 상한. 0 이하면 무제한. */
    private final int maxPerOwner;
    /** 소유자 1인당 동시 공개(PUBLIC/대기 중) 서비스 상한. 0 이하면 무제한. */
    private final int maxPublicPerOwner;

    // TunnelProvider removed: tunnels are now managed by VM Agents, not by Relay.
    // TODO (issue #3): activate real TunnelProvider only after VM Agent or CloudStack
    //                  ownership verification is complete.
    public ServiceRegistry(DnsProvider dns, AgentRegistry agentRegistry, CloudStackProvider cloudStack,
                           ObjectMapper mapper,
                           @Value("${service.store.file:}") String storeFile,
                           @Value("${service.max-per-owner:10}") int maxPerOwner,
                           @Value("${service.max-public-per-owner:3}") int maxPublicPerOwner) {
        this.dns = dns;
        this.agentRegistry = agentRegistry;
        this.cloudStack = cloudStack;
        this.mapper = mapper;
        this.storeFile = storeFile == null ? "" : storeFile.trim();
        this.maxPerOwner = maxPerOwner;
        this.maxPublicPerOwner = maxPublicPerOwner;
    }

    @PostConstruct
    public void load() {
        if (storeFile.isEmpty() || !Files.exists(Path.of(storeFile))) return;
        try {
            ServiceEntrySnapshot[] snaps = mapper.readValue(Files.readAllBytes(Path.of(storeFile)),
                    ServiceEntrySnapshot[].class);
            for (ServiceEntrySnapshot s : snaps) {
                ServiceEntry e = new ServiceEntry(
                        s.id(), s.ownerId(), s.name(), s.instanceId(), s.privateIp(),
                        s.localPort(), Protocol.valueOf(s.protocol()), ServiceScope.valueOf(s.scope()),
                        ServiceStatus.valueOf(s.status()), AccessPolicy.valueOf(s.accessPolicy()),
                        s.allowedEmails(), s.internalHostname(), s.publicUrl(),
                        s.publicExpiresAt() != null ? Instant.parse(s.publicExpiresAt()) : null,
                        s.scopeBeforePublish() != null ? ServiceScope.valueOf(s.scopeBeforePublish()) : null,
                        AgentCommand.valueOf(s.pendingCommand()), s.agentId(),
                        Instant.parse(s.createdAt()), Instant.parse(s.updatedAt()));
                services.put(e.getId(), e);
                // 기동 시 내부 DNS(zone) 재구성 — INTERNAL/TEAM은 사설 IP A 레코드를 다시 등록
                if (e.getScope() == ServiceScope.INTERNAL || e.getScope() == ServiceScope.TEAM) {
                    try { dns.createRecord(e.getInternalHostname(), e.getPrivateIp()); } catch (Exception ignore) {}
                }
            }
            log.info("[SVC] loaded {} services from {}", services.size(), storeFile);
        } catch (Exception ex) {
            log.warn("[SVC] failed to load store {}: {}", storeFile, ex.getMessage());
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────────

    /**
     * 서비스 생성. A 레코드(DNS)와 동일하게 {@code instanceId}만 받아 CloudStack에서
     * 소유권·사설 IP를 채운다(수동 IP 입력·자기신고 금지). 소유자(=학번)는 SOLID 세션에서 온다.
     */
    public ServiceEntry create(SolidIdentity identity, CreateServiceRequest req) {
        String ownerId = identity.account();
        validateCreate(ownerId, req);
        VmInfo vm = cloudStack.findVm(identity, req.instanceId().trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "VM을 찾을 수 없거나 접근 권한이 없습니다: " + req.instanceId()));
        if (vm.account() != null && identity.account() != null
                && !vm.account().equals(identity.account()))
            throw new IllegalArgumentException("해당 VM의 소유자가 아닙니다.");
        ServiceEntry entry = new ServiceEntry(
                ownerId, req.name(), vm.instanceId(), vm.privateIp(),
                req.localPort(), req.protocol(), req.scope()
        );
        if (req.accessPolicy() != null) entry.setAccessPolicy(req.accessPolicy());
        if (req.allowedEmails() != null) entry.setAllowedEmails(normalizeEmails(req.allowedEmails()));
        services.put(entry.getId(), entry);
        if (req.scope() == ServiceScope.INTERNAL || req.scope() == ServiceScope.TEAM) {
            dns.createRecord(entry.getInternalHostname(), entry.getPrivateIp());
        }
        persist();
        return entry;
    }

    public Optional<ServiceEntry> findById(String id) {
        return Optional.ofNullable(services.get(id));
    }

    /** 지표: 전체 서비스 수. */
    public long count() { return services.size(); }

    /** 지표: 외부 공개(PUBLIC) 서비스 수. */
    public long countPublic() {
        return services.values().stream()
                .filter(e -> e.getScope() == ServiceScope.PUBLIC).count();
    }

    public List<ServiceEntry> findByOwner(String ownerId) {
        return services.values().stream()
                .filter(s -> s.getOwnerId().equals(ownerId))
                .sorted(Comparator.comparing(ServiceEntry::getCreatedAt).reversed())
                .toList();
    }

    public Optional<ServiceEntry> update(String id, String ownerId, UpdateServiceRequest req) {
        ServiceEntry entry = services.get(id);
        if (entry == null || !entry.getOwnerId().equals(ownerId)) return Optional.empty();

        if (req.name() != null) {
            validateName(req.name());
            boolean nameTaken = services.values().stream()
                    .anyMatch(s -> s.getOwnerId().equals(ownerId)
                            && !s.getId().equals(id)
                            && s.getName().equals(req.name()));
            if (nameTaken) throw new IllegalArgumentException("Service name already in use: " + req.name());

            String oldHostname = entry.getInternalHostname();
            entry.setName(req.name());
            if (entry.getScope() == ServiceScope.INTERNAL || entry.getScope() == ServiceScope.TEAM) {
                dns.deleteRecord(oldHostname);
                dns.createRecord(entry.getInternalHostname(), entry.getPrivateIp());
            }
        }
        if (req.scope() != null) {
            if (req.scope() == ServiceScope.PUBLIC)
                throw new IllegalArgumentException("PUBLIC scope cannot be set directly; use POST /publish");

            if (entry.getPendingCommand() == AgentCommand.OPEN_TUNNEL) {
                // Cancel the pending tunnel-open request
                entry.setPendingCommand(AgentCommand.NONE);
                entry.setStatus(ServiceStatus.UNKNOWN);
                entry.setPublicExpiresAt(null);
                entry.setScopeBeforePublish(null);
            } else if (entry.getScope() == ServiceScope.PUBLIC) {
                // Tunnel is active — signal agent to close it
                entry.setPendingCommand(AgentCommand.CLOSE_TUNNEL);
                entry.setPublicUrl(null);
                entry.setPublicExpiresAt(null);
                entry.setScopeBeforePublish(null);
                entry.setAgentId(null);
            }
            ServiceScope oldScope = entry.getScope();
            entry.setScope(req.scope());
            updateDnsOnScopeChange(entry, oldScope, req.scope());
        }
        if (req.accessPolicy() != null) entry.setAccessPolicy(req.accessPolicy());
        if (req.allowedEmails() != null) entry.setAllowedEmails(normalizeEmails(req.allowedEmails()));
        persist();
        return Optional.of(entry);
    }

    public boolean delete(String id, String ownerId) {
        ServiceEntry entry = services.get(id);
        if (entry == null || !entry.getOwnerId().equals(ownerId)) return false;

        // If a tunnel is running (PUBLIC or waiting to close), tell the agent to shut it down.
        // The service will be gone from the store, so we park the command in the agent registry.
        if (entry.getScope() == ServiceScope.PUBLIC ||
                entry.getPendingCommand() == AgentCommand.CLOSE_TUNNEL) {
            agentRegistry.addOrphanClose(entry.getInstanceId(), entry.getOwnerId(),
                    new AgentHeartbeatResponse.TunnelCommand(
                            entry.getId(), "CLOSE_TUNNEL",
                            entry.getLocalPort(), entry.getPublicUrl()));
        }

        services.remove(id);
        dns.deleteRecord(entry.getInternalHostname());
        persist();
        return true;
    }

    // ── Publish / Unpublish ───────────────────────────────────────────────────────

    /**
     * Requests an external tunnel for this service.
     * Sets status to PENDING and records an OPEN_TUNNEL command that the VM Agent
     * will pick up on its next heartbeat. The scope is NOT changed to PUBLIC here;
     * that happens when the agent calls back with {@link #onTunnelReady}.
     */
    public Optional<ServiceEntry> publish(String id, String ownerId, int ttlHours) {
        ServiceEntry entry = services.get(id);
        if (entry == null || !entry.getOwnerId().equals(ownerId)) return Optional.empty();

        // 이미 공개(또는 공개 대기) 상태가 아닌 새 공개 요청만 상한에 계산한다(재publish 허용).
        boolean alreadyPublic = entry.getScope() == ServiceScope.PUBLIC
                || entry.getPendingCommand() == AgentCommand.OPEN_TUNNEL;
        if (maxPublicPerOwner > 0 && !alreadyPublic) {
            long activePublic = services.values().stream()
                    .filter(s -> s.getOwnerId().equals(ownerId) && !s.getId().equals(id))
                    .filter(s -> s.getScope() == ServiceScope.PUBLIC
                            || s.getPendingCommand() == AgentCommand.OPEN_TUNNEL)
                    .count();
            if (activePublic >= maxPublicPerOwner)
                throw new IllegalArgumentException(
                        "동시 공개 서비스 개수 상한(" + maxPublicPerOwner + "개)을 초과했습니다.");
        }

        int clampedTtl = Math.min(Math.max(ttlHours, 1), MAX_TTL_HOURS);

        // Save pre-publish scope so we can restore it on unpublish/failure
        if (entry.getScope() != ServiceScope.PUBLIC && entry.getPendingCommand() == AgentCommand.NONE) {
            entry.setScopeBeforePublish(entry.getScope());
        }
        entry.setStatus(ServiceStatus.PENDING);
        entry.setPendingCommand(AgentCommand.OPEN_TUNNEL);
        entry.setPublicExpiresAt(Instant.now().plusSeconds(clampedTtl * 3600L));
        persist();
        return Optional.of(entry);
    }

    /**
     * Cancels a pending publish or requests tunnel shutdown for an active public service.
     */
    public Optional<ServiceEntry> unpublish(String id, String ownerId) {
        ServiceEntry entry = services.get(id);
        if (entry == null || !entry.getOwnerId().equals(ownerId)) return Optional.empty();

        if (entry.getPendingCommand() == AgentCommand.OPEN_TUNNEL) {
            // Agent has not opened the tunnel yet — cancel immediately
            entry.setPendingCommand(AgentCommand.NONE);
            entry.setStatus(ServiceStatus.UNKNOWN);
            entry.setPublicExpiresAt(null);
            restorePrePublishScope(entry);
        } else if (entry.getScope() == ServiceScope.PUBLIC) {
            // Tunnel is active — tell agent to close it
            entry.setPendingCommand(AgentCommand.CLOSE_TUNNEL);
        }
        persist();
        return Optional.of(entry);
    }

    // ── Agent callbacks ──────────────────────────────────────────────────────────

    /**
     * Agent reports that the cloudflared tunnel is up and ready.
     * Verifies that the reporting agent owns both the service and the instance
     * to prevent cross-instance or cross-user URL injection.
     */
    public void onTunnelReady(String agentId, String agentInstanceId, String agentOwnerId,
                              String serviceId, String publicUrl) {
        ServiceEntry entry = services.get(serviceId);
        if (entry == null || entry.getPendingCommand() != AgentCommand.OPEN_TUNNEL) return;
        if (!agentInstanceId.equals(entry.getInstanceId()) ||
                !agentOwnerId.equals(entry.getOwnerId())) return;
        entry.setScope(ServiceScope.PUBLIC);
        entry.setPublicUrl(publicUrl);
        entry.setStatus(ServiceStatus.ONLINE);
        entry.setPendingCommand(AgentCommand.NONE);
        entry.setAgentId(agentId);
        persist();
    }

    /**
     * Agent reports that the cloudflared tunnel has been stopped.
     * Verifies ownership to prevent a rogue agent from prematurely closing another user's tunnel.
     */
    public void onTunnelStopped(String agentId, String agentInstanceId, String agentOwnerId,
                                String serviceId) {
        ServiceEntry entry = services.get(serviceId);
        if (entry == null) return;
        if (!agentInstanceId.equals(entry.getInstanceId()) ||
                !agentOwnerId.equals(entry.getOwnerId())) return;
        entry.setPendingCommand(AgentCommand.NONE);
        // Only restore scope if we're still in the published state (a concurrent
        // PATCH might have already changed the scope).
        if (entry.getScope() == ServiceScope.PUBLIC) {
            restorePrePublishScope(entry);
            entry.setPublicUrl(null);
            entry.setPublicExpiresAt(null);
        }
        entry.setStatus(ServiceStatus.UNKNOWN);
        entry.setAgentId(null);
        persist();
    }

    /**
     * Agent reports that the tunnel failed to open.
     * Verifies ownership to prevent a rogue agent from injecting failures.
     */
    public void onTunnelFailed(String agentId, String agentInstanceId, String agentOwnerId,
                               String serviceId, String reason) {
        ServiceEntry entry = services.get(serviceId);
        if (entry == null) return;
        if (!agentInstanceId.equals(entry.getInstanceId()) ||
                !agentOwnerId.equals(entry.getOwnerId())) return;
        entry.setPendingCommand(AgentCommand.NONE);
        restorePrePublishScope(entry);
        entry.setPublicUrl(null);
        entry.setPublicExpiresAt(null);
        entry.setStatus(ServiceStatus.OFFLINE);
        entry.setAgentId(null);
        persist();
    }

    /**
     * Returns pending tunnel commands for the given agent (instanceId + ownerId).
     * Filtering by ownerId prevents an agent registered under user A from seeing
     * commands for services owned by user B, even on the same VM instance.
     */
    public List<AgentHeartbeatResponse.TunnelCommand> getPendingCommandsForAgent(
            String instanceId, String ownerId) {
        return services.values().stream()
                .filter(e -> instanceId.equals(e.getInstanceId()))
                .filter(e -> ownerId.equals(e.getOwnerId()))
                .filter(e -> e.getPendingCommand() != AgentCommand.NONE)
                .map(e -> new AgentHeartbeatResponse.TunnelCommand(
                        e.getId(),
                        e.getPendingCommand().name(),
                        e.getLocalPort(),
                        e.getPublicUrl()
                ))
                .toList();
    }

    // ── Scheduled cleanup ────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 300_000)
    public void cleanExpiredPublic() {
        Instant now = Instant.now();
        boolean[] changed = {false};
        services.values().stream()
                .filter(e -> e.getPublicExpiresAt() != null && now.isAfter(e.getPublicExpiresAt()))
                .forEach(e -> {
                    if (e.getPendingCommand() == AgentCommand.OPEN_TUNNEL) {
                        // Agent never responded before TTL ran out — cancel
                        e.setPendingCommand(AgentCommand.NONE);
                        e.setStatus(ServiceStatus.UNKNOWN);
                        e.setPublicExpiresAt(null);
                        restorePrePublishScope(e);
                        changed[0] = true;
                    } else if (e.getScope() == ServiceScope.PUBLIC) {
                        if (agentRegistry.isInstanceAlive(e.getInstanceId())) {
                            // Agent is alive — ask it to close the tunnel
                            e.setPendingCommand(AgentCommand.CLOSE_TUNNEL);
                        } else {
                            // No live agent — clean up immediately
                            restorePrePublishScope(e);
                            e.setPublicUrl(null);
                            e.setPublicExpiresAt(null);
                            e.setStatus(ServiceStatus.UNKNOWN);
                            e.setPendingCommand(AgentCommand.NONE);
                            e.setAgentId(null);
                        }
                        changed[0] = true;
                    }
                });
        if (changed[0]) persist();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private void restorePrePublishScope(ServiceEntry entry) {
        ServiceScope restored = entry.getScopeBeforePublish() != null
                ? entry.getScopeBeforePublish()
                : ServiceScope.PRIVATE;
        entry.setScope(restored);
        entry.setScopeBeforePublish(null);
    }

    /** 허용 이메일 목록 정규화: trim·소문자·공백 제거·중복 제거. */
    private List<String> normalizeEmails(List<String> emails) {
        return emails.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(e -> e.trim().toLowerCase())
                .distinct()
                .toList();
    }

    private void validateCreate(String ownerId, CreateServiceRequest req) {
        if (req.name() == null || req.name().isBlank())
            throw new IllegalArgumentException("Service name is required");
        validateName(req.name());
        if (req.instanceId() == null || req.instanceId().isBlank())
            throw new IllegalArgumentException("Instance ID is required");
        if (req.localPort() < 1 || req.localPort() > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (req.protocol() == null)
            throw new IllegalArgumentException("Protocol is required");
        if (req.scope() == null)
            throw new IllegalArgumentException("Scope is required");
        if (req.scope() == ServiceScope.PUBLIC)
            throw new IllegalArgumentException("PUBLIC scope cannot be set directly; use POST /publish");
        if (maxPerOwner > 0 && findByOwner(ownerId).size() >= maxPerOwner)
            throw new IllegalArgumentException("서비스 개수 상한(" + maxPerOwner + "개)을 초과했습니다.");
        boolean nameTaken = services.values().stream()
                .anyMatch(s -> s.getOwnerId().equals(ownerId) && s.getName().equals(req.name()));
        if (nameTaken) throw new IllegalArgumentException("Service name already in use: " + req.name());
    }

    private void validateName(String name) {
        if (!NAME_PATTERN.matcher(name).matches())
            throw new IllegalArgumentException(
                    "Service name must be lowercase alphanumeric and hyphens (1-50 chars): " + name);
    }

    private void updateDnsOnScopeChange(ServiceEntry entry, ServiceScope from, ServiceScope to) {
        boolean wasInternal = from == ServiceScope.INTERNAL || from == ServiceScope.TEAM;
        boolean isInternal  = to   == ServiceScope.INTERNAL || to   == ServiceScope.TEAM;
        if (!wasInternal && isInternal) {
            dns.createRecord(entry.getInternalHostname(), entry.getPrivateIp());
        } else if (wasInternal && !isInternal) {
            dns.deleteRecord(entry.getInternalHostname());
        }
    }

    private void persist() {
        if (storeFile.isEmpty()) return;
        try {
            List<ServiceEntrySnapshot> snaps = services.values().stream().map(e -> new ServiceEntrySnapshot(
                    e.getId(), e.getOwnerId(), e.getName(), e.getInstanceId(), e.getPrivateIp(),
                    e.getLocalPort(), e.getProtocol().name(), e.getScope().name(), e.getStatus().name(),
                    e.getAccessPolicy().name(), e.getAllowedEmails(), e.getInternalHostname(),
                    e.getPublicUrl(),
                    e.getPublicExpiresAt() != null ? e.getPublicExpiresAt().toString() : null,
                    e.getScopeBeforePublish() != null ? e.getScopeBeforePublish().name() : null,
                    e.getPendingCommand().name(), e.getAgentId(),
                    e.getCreatedAt().toString(), e.getUpdatedAt().toString())).toList();
            Path file = Path.of(storeFile);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(snaps));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("[SVC] failed to persist store {}: {}", storeFile, e.getMessage());
        }
    }
}
