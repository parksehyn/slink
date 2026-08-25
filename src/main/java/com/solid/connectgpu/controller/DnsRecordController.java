package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.ApiError;
import com.solid.connectgpu.dto.CreateDnsRecordRequest;
import com.solid.connectgpu.dto.DnsRecordResponse;
import com.solid.connectgpu.dto.UpdateDnsRecordRequest;
import com.solid.connectgpu.model.DnsRecord;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.service.AuthService;
import com.solid.connectgpu.service.DnsApiException;
import com.solid.connectgpu.service.DnsRecordRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 내부 DNS 레코드(A/CNAME) API. SOLID 세션 토큰으로 인증하고(소유자=학번),
 * A 레코드는 vmId로 생성하면 서버가 CloudStack에서 IP·소유권을 검증한다.
 * 에러는 표준 {@link ApiError} 엔벨로프로 반환한다(명세서 §10).
 */
@RestController
@RequestMapping("/api/dns/records")
public class DnsRecordController {

    private final DnsRecordRegistry registry;
    private final AuthService authService;

    public DnsRecordController(DnsRecordRegistry registry, AuthService authService) {
        this.registry = registry;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return unauthorized();
        return ResponseEntity.ok(
                registry.findByOwner(id.account()).stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @PathVariable("id") String recordId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return unauthorized();
        return registry.findById(recordId)
                .filter(r -> r.getOwnerId().equals(id.account()))
                .map(r -> ResponseEntity.ok((Object) toResponse(r)))
                .orElse(ResponseEntity.status(404).body((Object) ApiError.of("NOT_FOUND", "레코드를 찾을 수 없습니다.")));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody CreateDnsRecordRequest req) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return unauthorized();
        try {
            DnsRecord record = registry.create(id, req);
            return ResponseEntity.status(201).body(toResponse(record));
        } catch (DnsApiException e) {
            return error(e);
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable("id") String recordId,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody UpdateDnsRecordRequest req) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return unauthorized();
        try {
            return registry.update(recordId, id.account(), req)
                    .map(r -> ResponseEntity.ok((Object) toResponse(r)))
                    .orElse(ResponseEntity.status(404).body((Object) ApiError.of("NOT_FOUND", "레코드를 찾을 수 없습니다.")));
        } catch (DnsApiException e) {
            return error(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable("id") String recordId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return unauthorized();
        return registry.delete(recordId, id.account())
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(404).body(ApiError.of("NOT_FOUND", "레코드를 찾을 수 없습니다."));
    }

    private SolidIdentity authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return authService.resolve(auth.substring(7)).orElse(null);
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(401).body(ApiError.of("UNAUTHORIZED", "인증이 필요합니다."));
    }

    private ResponseEntity<?> error(DnsApiException e) {
        return ResponseEntity.status(e.getHttpStatus()).body(ApiError.of(e.getCode(), e.getMessage()));
    }

    private DnsRecordResponse toResponse(DnsRecord r) {
        return new DnsRecordResponse(
                r.getId(),
                r.getOwnerId(),
                r.getType().name(),
                r.getName(),
                r.getFqdn(),
                r.getValue(),
                r.getTtl(),
                r.getVmId(),
                r.getVmName(),
                r.getStatus().name(),
                r.getCreatedAt().toString(),
                r.getUpdatedAt().toString()
        );
    }
}
