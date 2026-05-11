package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.RegisterRequest;
import com.solid.connectgpu.dto.SessionResponse;
import com.solid.connectgpu.model.Session;
import com.solid.connectgpu.service.SessionService;
import com.solid.connectgpu.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/session")
@Tag(name = "Session", description = "Colab GPU 세션 관리 API")
public class SessionController {

    private final SessionService sessionService;
    private final UserService userService;

    public SessionController(SessionService sessionService, UserService userService) {
        this.sessionService = sessionService;
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Colab 에이전트가 세션을 등록합니다 (owner 기반, 코드 폴백 유지)")
    public ResponseEntity<SessionResponse> register(@RequestBody RegisterRequest request) {
        Session session = sessionService.register(
                request.owner(), request.ngrokHost(), request.sshPort(), request.otp(), request.jupyterToken()
        );
        return ResponseEntity.ok(toResponse(session));
    }

    @GetMapping("/{code}")
    @Operation(summary = "6자리 코드로 세션 조회 (폴백)")
    public ResponseEntity<SessionResponse> get(@PathVariable String code) {
        return sessionService.find(code)
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "6자리 코드로 세션 삭제 (폴백)")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        sessionService.remove(code);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/by-owner/{email}")
    @Operation(summary = "소유자 이메일로 세션 조회 (Bearer 인증)")
    public ResponseEntity<SessionResponse> getByOwner(
            @PathVariable String email,
            @RequestHeader("Authorization") String auth) {
        if (!verifyBearer(email, auth)) return ResponseEntity.status(401).build();
        return sessionService.findByOwner(email)
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/by-owner/{email}")
    @Operation(summary = "소유자 세션 삭제 (Bearer 인증)")
    public ResponseEntity<Void> deleteByOwner(
            @PathVariable String email,
            @RequestHeader("Authorization") String auth) {
        if (!verifyBearer(email, auth)) return ResponseEntity.status(401).build();
        sessionService.removeByOwner(email);
        return ResponseEntity.noContent().build();
    }

    private boolean verifyBearer(String email, String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return false;
        return userService.verify(email, auth.substring(7));
    }

    private SessionResponse toResponse(Session s) {
        return new SessionResponse(
                s.getCode(), s.getNgrokHost(), s.getSshPort(), s.getOtp(), s.getJupyterToken(), s.getExpiresAt()
        );
    }
}
