package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.ApiError;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.port.CloudStackProvider;
import com.solid.connectgpu.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 현재 사용자가 접근 가능한 SOLID VM 목록. DNS 레코드 등록 시 vmId 선택에 사용한다.
 * SOLID 세션 토큰으로 인증하며, 목록은 그 신원의 CloudStack 세션으로 조회한다.
 */
@RestController
@RequestMapping("/api/vms")
public class VmController {

    private final CloudStackProvider cloudStack;
    private final AuthService authService;

    public VmController(CloudStackProvider cloudStack, AuthService authService) {
        this.cloudStack = cloudStack;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity id = authenticate(auth);
        if (id == null) return ResponseEntity.status(401).body(ApiError.of("UNAUTHORIZED", "인증이 필요합니다."));
        return ResponseEntity.ok(cloudStack.listVms(id));
    }

    private SolidIdentity authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return authService.resolve(auth.substring(7)).orElse(null);
    }
}
