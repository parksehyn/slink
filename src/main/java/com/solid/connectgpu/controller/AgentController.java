package com.solid.connectgpu.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@RestController
public class AgentController {

    private final String scriptTemplate;
    private final String scriptTemplateCf;

    public AgentController() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/agent.py")) {
            scriptTemplate = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream is = getClass().getResourceAsStream("/agent_cf.py")) {
            scriptTemplateCf = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @GetMapping(value = "/agent", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> agent(HttpServletRequest request) {
        String relayUrl = resolveBaseUrl(request);
        String script = scriptTemplate.replace("{{RELAY_URL}}", relayUrl);
        return ResponseEntity.ok(script);
    }

    @GetMapping(value = "/agent-cf", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> agentCf(HttpServletRequest request) {
        String relayUrl = resolveBaseUrl(request);
        String script = scriptTemplateCf.replace("{{RELAY_URL}}", relayUrl);
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
