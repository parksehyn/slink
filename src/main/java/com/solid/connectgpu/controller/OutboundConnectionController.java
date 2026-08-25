package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.ConnectionResponse;
import com.solid.connectgpu.dto.CreateConnectionRequest;
import com.solid.connectgpu.model.OutboundConnection;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.service.AuthService;
import com.solid.connectgpu.service.OutboundConnectionRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 아웃바운드 외부 연결 API. 외부 서비스가 열어준 터널 주소를 등록·조회·삭제한다.
 * 기존 Colab 세션 API(/api/session)는 그대로 유지되며, 이 API는 임의 외부 서비스로의
 * 일반화된 연결을 관리한다. 인증은 SOLID 세션 토큰(slk-, 소유자=학번)을 사용한다.
 */
@RestController
@RequestMapping("/api/connections")
public class OutboundConnectionController {

    private final OutboundConnectionRegistry registry;
    private final AuthService authService;

    public OutboundConnectionController(OutboundConnectionRegistry registry, AuthService authService) {
        this.registry = registry;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<List<ConnectionResponse>> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(
                registry.findByOwner(id.account()).stream().map(this::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody CreateConnectionRequest req) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return ResponseEntity.status(401).build();
        try {
            OutboundConnection conn = registry.create(id.account(), req);
            return ResponseEntity.status(201).body(toResponse(conn));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity identity = authenticate(auth);
        if (identity == null) return ResponseEntity.status(401).build();
        return registry.delete(id, identity.account())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private SolidIdentity authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return authService.resolve(auth.substring(7)).orElse(null);
    }

    private ConnectionResponse toResponse(OutboundConnection c) {
        return new ConnectionResponse(
                c.getId(),
                c.getOwnerId(),
                c.getName(),
                c.getType().name(),
                c.getUrl(),
                c.getToken(),
                c.getNote(),
                c.getCreatedAt().toString()
        );
    }
}
