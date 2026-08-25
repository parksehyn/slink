package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.ResourceAgentRegisterRequest;
import com.solid.connectgpu.dto.ResourceAgentRegisterResponse;
import com.solid.connectgpu.dto.ResourceHeartbeatResponse;
import com.solid.connectgpu.dto.ResourceReportRequest;
import com.solid.connectgpu.model.Agent;
import com.solid.connectgpu.model.ResourceStatus;
import com.solid.connectgpu.service.AgentRegistry;
import com.solid.connectgpu.service.RegistrationTokenRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 외부 자원 에이전트 대면 API. 인바운드 {@code /api/agents}의 거울상이되, 명령 큐가 없다
 * (에이전트가 자기 터널 URL의 source of truth — 자율적으로 열고 self-report).
 * 저장은 통합 {@link AgentRegistry}(location=COLAB/EXTERNAL) — M2에서 경로도 /api/agents로 수렴 예정.
 *
 * <p>등록은 단기 토큰 {@code X-Registration-Token: rt-}로 1회, 이후 heartbeat/report는 영속 토큰
 * {@code X-Agent-Token: rat-}로 한다. ownerId는 등록 토큰(grant)에서만 도출한다(클라이언트 body 불신).
 */
@RestController
@RequestMapping("/api/resources/agents")
public class ExternalResourceAgentController {

    private final AgentRegistry registry;
    private final RegistrationTokenRegistry tokens;

    public ExternalResourceAgentController(AgentRegistry registry,
                                           RegistrationTokenRegistry tokens) {
        this.registry = registry;
        this.tokens = tokens;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestHeader(value = "X-Registration-Token", required = false) String regToken,
            @RequestBody(required = false) ResourceAgentRegisterRequest req) {
        Optional<RegistrationTokenRegistry.Grant> grant = tokens.consume(regToken);
        if (grant.isEmpty())
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired registration token");
        try {
            Agent a = registry.createFromGrant(grant.get(), req);
            return ResponseEntity.status(HttpStatus.CREATED).body(new ResourceAgentRegisterResponse(
                    a.getAgentId(), a.getAgentToken(),
                    a.primaryService().getType().name(), a.primaryService().getName()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/{resourceId}/heartbeat")
    public ResponseEntity<ResourceHeartbeatResponse> heartbeat(
            @PathVariable String resourceId,
            @RequestHeader(value = "X-Agent-Token", required = false) String token) {
        Agent a = registry.validateExternal(resourceId, token).orElse(null);
        if (a == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        boolean revoked = a.primaryService() != null
                && a.primaryService().effectiveStatus(a.isAlive()) == ResourceStatus.STOPPED;
        return ResponseEntity.ok(new ResourceHeartbeatResponse(revoked));
    }

    @PostMapping("/{resourceId}/report")
    public ResponseEntity<?> report(
            @PathVariable String resourceId,
            @RequestHeader(value = "X-Agent-Token", required = false) String token,
            @RequestBody ResourceReportRequest req) {
        Agent a = registry.validateExternal(resourceId, token).orElse(null);
        if (a == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            registry.report(a, req);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
