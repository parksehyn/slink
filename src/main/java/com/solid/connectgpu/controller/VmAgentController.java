package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.AgentHeartbeatResponse;
import com.solid.connectgpu.dto.AgentRegisterRequest;
import com.solid.connectgpu.dto.AgentRegisterResponse;
import com.solid.connectgpu.dto.AgentReportRequest;
import com.solid.connectgpu.dto.DeviceTokenRequest;
import com.solid.connectgpu.dto.DeviceTokenResponse;
import com.solid.connectgpu.model.Agent;
import com.solid.connectgpu.model.RegistrationTokenKind;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.service.AgentRegistry;
import com.solid.connectgpu.service.AuthService;
import com.solid.connectgpu.service.RegistrationTokenRegistry;
import com.solid.connectgpu.service.ServiceRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/agents")
public class VmAgentController {

    private final AgentRegistry agentRegistry;
    private final ServiceRegistry serviceRegistry;
    private final AuthService authService;
    private final RegistrationTokenRegistry tokens;

    public VmAgentController(AgentRegistry agentRegistry,
                             ServiceRegistry serviceRegistry,
                             AuthService authService,
                             RegistrationTokenRegistry tokens) {
        this.agentRegistry = agentRegistry;
        this.serviceRegistry = serviceRegistry;
        this.authService = authService;
        this.tokens = tokens;
    }

    /**
     * 포털(SOLID 인증)이 특정 VM의 헤드리스 에이전트 등록용 단기 토큰(rt-)을 발급한다.
     * VM에서 비밀번호 없이 {@code slink agent enroll --token rt-}로 1회 교환 → 영속 {@code at-} 토큰.
     */
    @PostMapping("/device-token")
    public ResponseEntity<?> deviceToken(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody DeviceTokenRequest req) {
        SolidIdentity identity = authenticate(auth);
        if (identity == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (req.instanceId() == null || req.instanceId().isBlank())
            return ResponseEntity.badRequest().body("instanceId is required");
        RegistrationTokenRegistry.Grant g = tokens.issue(
                identity.account(), RegistrationTokenKind.INBOUND_DEVICE,
                null, req.instanceId().trim(), null, null);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new DeviceTokenResponse(g.token(), req.instanceId().trim(), g.expiresAt().toString()));
    }

    /**
     * 헤드리스 enroll: 디바이스 토큰(rt-)을 영속 에이전트 토큰(at-)으로 교환한다.
     * ownerId·instanceId는 grant에서 가져온다(SOLID 비밀번호 불필요).
     */
    @PostMapping("/enroll")
    public ResponseEntity<?> enroll(
            @RequestHeader(value = "X-Registration-Token", required = false) String regToken) {
        Optional<RegistrationTokenRegistry.Grant> grant = tokens.consume(regToken);
        if (grant.isEmpty() || grant.get().kind() != RegistrationTokenKind.INBOUND_DEVICE
                || grant.get().instanceId() == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired device token");
        Agent agent = agentRegistry.register(grant.get().instanceId(), grant.get().ownerId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AgentRegisterResponse(agent.getAgentId(), agent.getAgentToken(), agent.getInstanceId()));
    }

    /** 이 학생의 VM 중 에이전트가 온라인인 instanceId 목록 (포털 per-VM 표시용). */
    @GetMapping("/online")
    public ResponseEntity<List<String>> online(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity identity = authenticate(auth);
        if (identity == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(new ArrayList<>(agentRegistry.aliveInstanceIds(identity.account())));
    }

    /**
     * VM Agent registers itself with the Relay.
     *
     * Requires a valid SOLID session token (slk-) so the agent is bound to a specific student
     * account (owner = CloudStack account/학번). Once registered, the agent uses the issued
     * agent token (at-) for heartbeat/report — the SOLID session is only needed at registration.
     * Commands delivered via heartbeat are scoped to services owned by that student on the
     * declared instanceId — an agent cannot observe or act on another student's services.
     *
     * TODO (security, Phase 1B): verify instanceId ownership via CloudStack API at registration.
     *                  Cross-user leakage is already prevented by (instanceId, ownerId) scoping;
     *                  this would additionally stop an agent binding to a VM the student does not own.
     */
    @PostMapping("/register")
    public ResponseEntity<AgentRegisterResponse> register(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody AgentRegisterRequest req) {
        SolidIdentity identity = authenticate(auth);
        if (identity == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (req.instanceId() == null || req.instanceId().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "instanceId is required");

        Agent agent = agentRegistry.register(req.instanceId(), identity.account());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AgentRegisterResponse(
                        agent.getAgentId(),
                        agent.getAgentToken(),
                        agent.getInstanceId()));
    }

    /**
     * Agent heartbeat — records the agent as alive and returns any pending tunnel commands
     * for services the agent's owner has on the agent's declared instanceId.
     * Also delivers orphaned CLOSE_TUNNEL commands for services deleted while their tunnel
     * was still active.
     */
    @PostMapping("/{agentId}/heartbeat")
    public ResponseEntity<AgentHeartbeatResponse> heartbeat(
            @PathVariable String agentId,
            @RequestHeader("X-Agent-Token") String token) {
        Agent agent = agentRegistry.validateVm(agentId, token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid agent credentials"));

        List<AgentHeartbeatResponse.TunnelCommand> pending =
                serviceRegistry.getPendingCommandsForAgent(agent.getInstanceId(), agent.getOwnerId());
        List<AgentHeartbeatResponse.TunnelCommand> orphans =
                agentRegistry.drainOrphanCloses(agent.getInstanceId(), agent.getOwnerId());

        List<AgentHeartbeatResponse.TunnelCommand> commands =
                Stream.concat(pending.stream(), orphans.stream()).toList();
        return ResponseEntity.ok(new AgentHeartbeatResponse(commands));
    }

    /**
     * Agent reports a tunnel lifecycle event:
     * <ul>
     *   <li>TUNNEL_READY   — tunnel is up; publicUrl is the trycloudflare.com address</li>
     *   <li>TUNNEL_STOPPED — tunnel has been closed</li>
     *   <li>TUNNEL_FAILED  — tunnel could not be opened; reason describes the error</li>
     * </ul>
     * The agent's instanceId and ownerId are verified inside each ServiceRegistry callback
     * to prevent cross-instance and cross-user URL injection or forced shutdowns.
     */
    @PostMapping("/{agentId}/report")
    public ResponseEntity<Void> report(
            @PathVariable String agentId,
            @RequestHeader("X-Agent-Token") String token,
            @RequestBody AgentReportRequest req) {
        Agent agent = agentRegistry.validateVm(agentId, token)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Invalid agent credentials"));
        switch (req.event()) {
            case "TUNNEL_READY"   -> serviceRegistry.onTunnelReady(
                    agent.getAgentId(), agent.getInstanceId(), agent.getOwnerId(),
                    req.serviceId(), req.publicUrl());
            case "TUNNEL_STOPPED" -> serviceRegistry.onTunnelStopped(
                    agent.getAgentId(), agent.getInstanceId(), agent.getOwnerId(),
                    req.serviceId());
            case "TUNNEL_FAILED"  -> serviceRegistry.onTunnelFailed(
                    agent.getAgentId(), agent.getInstanceId(), agent.getOwnerId(),
                    req.serviceId(), req.reason());
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Unknown event type: " + req.event());
        }
        return ResponseEntity.ok().build();
    }

    private SolidIdentity authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return authService.resolve(auth.substring(7)).orElse(null);
    }
}
