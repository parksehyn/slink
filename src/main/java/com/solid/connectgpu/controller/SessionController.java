package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.RegisterRequest;
import com.solid.connectgpu.dto.SessionResponse;
import com.solid.connectgpu.model.Session;
import com.solid.connectgpu.service.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/session")
@Tag(name = "Session", description = "Colab GPU 세션 관리 API")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/register")
    @Operation(summary = "Colab 에이전트가 세션을 등록하고 6자리 코드를 발급받습니다")
    public ResponseEntity<SessionResponse> register(@RequestBody RegisterRequest request) {
        Session session = sessionService.register(
                request.ngrokHost(), request.sshPort(), request.otp(), request.jupyterToken()
        );
        return ResponseEntity.ok(toResponse(session));
    }

    @GetMapping("/{code}")
    @Operation(summary = "slink CLI가 연결 코드로 세션 정보를 조회합니다")
    public ResponseEntity<SessionResponse> get(@PathVariable String code) {
        return sessionService.find(code)
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Colab 세션 종료 시 정리합니다")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        sessionService.remove(code);
        return ResponseEntity.noContent().build();
    }

    private SessionResponse toResponse(Session s) {
        return new SessionResponse(
                s.getCode(), s.getNgrokHost(), s.getSshPort(), s.getOtp(), s.getJupyterToken()
        );
    }
}
