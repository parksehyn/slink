package com.solid.connectgpu.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Tag(name = "Agent", description = "Colab 에이전트 배포 API")
@RestController
public class AgentController {

    private final String scriptTemplate;

    public AgentController() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/agent.py")) {
            scriptTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Operation(summary = "Colab 부트스트랩 스크립트 반환 (python - 로 바로 실행 가능)")
    @GetMapping(value = "/agent", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> agent(HttpServletRequest request) {
        String relayUrl = resolveBaseUrl(request);
        String script = scriptTemplate.replace("{{RELAY_URL}}", relayUrl);
        return ResponseEntity.ok(script);
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        String proto = Optional.ofNullable(request.getHeader("X-Forwarded-Proto"))
                .orElse(request.getScheme());
        String host = Optional.ofNullable(request.getHeader("X-Forwarded-Host"))
                .orElse(request.getHeader("Host"));
        return proto + "://" + host;
    }
}
