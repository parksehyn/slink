package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.ExternalResourceResponse;
import com.solid.connectgpu.dto.IssueRegistrationTokenRequest;
import com.solid.connectgpu.dto.RegistrationTokenResponse;
import com.solid.connectgpu.model.Agent;
import com.solid.connectgpu.model.AgentService;
import com.solid.connectgpu.model.RegistrationTokenKind;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.service.AgentRegistry;
import com.solid.connectgpu.service.AuthService;
import com.solid.connectgpu.service.RegistrationTokenRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 외부 자원 연결 API (SOLID 인증, 소유자=학번). 포털이 단기 등록 토큰을 발급하고 자원을 조회·삭제한다.
 * 에이전트 대면 등록/heartbeat/report는 {@link ExternalResourceAgentController}가 담당한다.
 */
@RestController
@RequestMapping("/api/resources")
public class ExternalResourceController {

    private final AgentRegistry registry;
    private final RegistrationTokenRegistry tokens;
    private final AuthService authService;

    public ExternalResourceController(AgentRegistry registry,
                                      RegistrationTokenRegistry tokens,
                                      AuthService authService) {
        this.registry = registry;
        this.tokens = tokens;
        this.authService = authService;
    }

    /** 외부 에이전트용 단기 등록 토큰(rt-) 발급. */
    @PostMapping("/registration-token")
    public ResponseEntity<?> issueToken(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody IssueRegistrationTokenRequest req) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return ResponseEntity.status(401).build();
        if (req.resourceType() == null)
            return ResponseEntity.badRequest().body("resourceType is required");
        if (req.name() == null || req.name().isBlank())
            return ResponseEntity.badRequest().body("name is required");
        RegistrationTokenRegistry.Grant g = tokens.issue(
                id.account(), RegistrationTokenKind.OUTBOUND_RESOURCE,
                req.resourceType(), null, req.name().trim(), req.ttlMinutes());
        return ResponseEntity.status(201).body(new RegistrationTokenResponse(
                g.token(), g.resourceType().name(), g.name(), g.expiresAt().toString()));
    }

    @GetMapping
    public ResponseEntity<List<ExternalResourceResponse>> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(registry.findExternalByOwner(id.account()).stream()
                .map(a -> toResponse(a, false)).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExternalResourceResponse> get(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity identity = authenticate(auth);
        if (identity == null) return ResponseEntity.status(401).build();
        return registry.findExternalById(id)
                .filter(a -> a.getOwnerId().equals(identity.account()))
                .map(a -> ResponseEntity.ok(toResponse(a, true)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity identity = authenticate(auth);
        if (identity == null) return ResponseEntity.status(401).build();
        return registry.deleteExternal(id, identity.account())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private SolidIdentity authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return authService.resolve(auth.substring(7)).orElse(null);
    }

    /** includeToken=false면 serviceToken을 마스킹(null)한다(목록). 상세에서는 true. */
    private ExternalResourceResponse toResponse(Agent a, boolean includeToken) {
        AgentService s = a.primaryService();
        return new ExternalResourceResponse(
                a.getAgentId(),
                a.getOwnerId(),
                s.getType().name(),
                s.getName(),
                s.effectiveStatus(a.isAlive()).name(),
                s.getPublicUrl(),
                includeToken ? s.getServiceToken() : null,
                s.getExpiresAt() != null ? s.getExpiresAt().toString() : null,
                a.getLastHeartbeatAt() != null ? a.getLastHeartbeatAt().toString() : null,
                a.getRegisteredAt().toString(),
                s.getUpdatedAt().toString()
        );
    }
}
