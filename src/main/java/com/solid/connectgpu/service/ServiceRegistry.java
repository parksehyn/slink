package com.solid.connectgpu.service;

import com.solid.connectgpu.dto.AgentHeartbeatResponse;
import com.solid.connectgpu.dto.CreateServiceRequest;
import com.solid.connectgpu.dto.UpdateServiceRequest;
import com.solid.connectgpu.model.*;
import com.solid.connectgpu.port.DnsProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ServiceRegistry {

    private static final int MAX_TTL_HOURS = 24;
    private static final java.util.regex.Pattern NAME_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$|^[a-z0-9]$");

    private final ConcurrentHashMap<String, ServiceEntry> services = new ConcurrentHashMap<>();
    private final DnsProvider dns;
    private final VmAgentRegistry agentRegistry;

    // TunnelProvider removed: tunnels are now managed by VM Agents, not by Relay.
    // TODO (issue #3): activate real TunnelProvider only after VM Agent or CloudStack
    //                  ownership verification is complete.
    public ServiceRegistry(DnsProvider dns, VmAgentRegistry agentRegistry) {
        this.dns = dns;
        this.agentRegistry = agentRegistry;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────────

    public ServiceEntry create(String ownerId, CreateServiceRequest req) {
        validateCreate(ownerId, req);
        ServiceEntry entry = new ServiceEntry(
                ownerId, req.name(), req.instanceId(), req.privateIp(),
                req.localPort(), req.protocol(), req.scope()
        );
        if (req.accessPolicy() != null) entry.setAccessPolicy(req.accessPolicy());
        if (req.allowedEmails() != null) entry.setAllowedEmails(normalizeEmails(req.allowedEmails()));
        services.put(entry.getId(), entry);
        if (req.scope() == ServiceScope.INTERNAL || req.scope() == ServiceScope.TEAM) {
            dns.createRecord(entry.getInternalHostname(), entry.getPrivateIp());
        }
        return entry;
    }

    public Optional<ServiceEntry> findById(String id) {
        return Optional.ofNullable(services.get(id));
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

        int clampedTtl = Math.min(Math.max(ttlHours, 1), MAX_TTL_HOURS);

        // Save pre-publish scope so we can restore it on unpublish/failure
        if (entry.getScope() != ServiceScope.PUBLIC && entry.getPendingCommand() == AgentCommand.NONE) {
            entry.setScopeBeforePublish(entry.getScope());
        }
        entry.setStatus(ServiceStatus.PENDING);
        entry.setPendingCommand(AgentCommand.OPEN_TUNNEL);
        entry.setPublicExpiresAt(Instant.now().plusSeconds(clampedTtl * 3600L));
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
        services.values().stream()
                .filter(e -> e.getPublicExpiresAt() != null && now.isAfter(e.getPublicExpiresAt()))
                .forEach(e -> {
                    if (e.getPendingCommand() == AgentCommand.OPEN_TUNNEL) {
                        // Agent never responded before TTL ran out — cancel
                        e.setPendingCommand(AgentCommand.NONE);
                        e.setStatus(ServiceStatus.UNKNOWN);
                        e.setPublicExpiresAt(null);
                        restorePrePublishScope(e);
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
                    }
                });
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
        if (req.privateIp() == null || req.privateIp().isBlank())
            throw new IllegalArgumentException("Private IP is required");
        if (req.localPort() < 1 || req.localPort() > 65535)
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        if (req.protocol() == null)
            throw new IllegalArgumentException("Protocol is required");
        if (req.scope() == null)
            throw new IllegalArgumentException("Scope is required");
        if (req.scope() == ServiceScope.PUBLIC)
            throw new IllegalArgumentException("PUBLIC scope cannot be set directly; use POST /publish");
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
}
