package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.ApiError;
import com.solid.connectgpu.dto.AuthMeResponse;
import com.solid.connectgpu.dto.AuthTokenResponse;
import com.solid.connectgpu.dto.LoginRequest;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * SOLID(CloudStack) 기반 인증. 학번/비밀번호로 로그인해 slink 세션 토큰을 받고,
 * 이후 DNS·VM API는 {@code Authorization: Bearer <token>}로 호출한다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req.username() == null || req.username().isBlank()
                || req.password() == null || req.password().isBlank())
            return ResponseEntity.badRequest()
                    .body(ApiError.of("INVALID_REQUEST", "username과 password가 필요합니다."));
        try {
            String token = authService.login(req.username(), req.password(), req.domain());
            SolidIdentity id = authService.resolve(token).orElseThrow();
            return ResponseEntity.ok(new AuthTokenResponse(token, id.account(), id.domain(), id.email()));
        } catch (Exception e) {
            return ResponseEntity.status(401)
                    .body(ApiError.of("UNAUTHORIZED", "SOLID 로그인 실패: 자격 증명을 확인하세요."));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return resolve(auth)
                .map(id -> ResponseEntity.ok(
                        (Object) new AuthMeResponse(id.account(), id.domain(), id.email())))
                .orElse(ResponseEntity.status(401).body((Object) ApiError.of("UNAUTHORIZED", "유효하지 않은 토큰")));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) authService.logout(auth.substring(7));
        return ResponseEntity.noContent().build();
    }

    private java.util.Optional<SolidIdentity> resolve(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return java.util.Optional.empty();
        return authService.resolve(auth.substring(7));
    }
}
