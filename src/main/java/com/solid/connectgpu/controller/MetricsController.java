package com.solid.connectgpu.controller;

import com.solid.connectgpu.dto.MetricsResponse;
import com.solid.connectgpu.model.AgentLocation;
import com.solid.connectgpu.model.SolidIdentity;
import com.solid.connectgpu.service.AgentRegistry;
import com.solid.connectgpu.service.AuthService;
import com.solid.connectgpu.service.DnsRecordRegistry;
import com.solid.connectgpu.service.OutboundConnectionRegistry;
import com.solid.connectgpu.service.ServiceRegistry;
import com.solid.connectgpu.service.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Relay 실시간 지표 (unified-agent-design.md §8.1, 1단계 — 레지스트리 현재 상태 집계).
 * 처리율·전파 지연 등 계측이 필요한 지표(§8.2)는 후속. SOLID 인증(포털과 동일).
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final AgentRegistry agents;
    private final ServiceRegistry services;
    private final DnsRecordRegistry dnsRecords;
    private final OutboundConnectionRegistry connections;
    private final SessionService sessions;
    private final AuthService authService;

    public MetricsController(AgentRegistry agents, ServiceRegistry services,
                             DnsRecordRegistry dnsRecords, OutboundConnectionRegistry connections,
                             SessionService sessions, AuthService authService) {
        this.agents = agents;
        this.services = services;
        this.dnsRecords = dnsRecords;
        this.connections = connections;
        this.sessions = sessions;
        this.authService = authService;
    }

    @GetMapping
    public ResponseEntity<MetricsResponse> metrics(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        SolidIdentity identity = authenticate(auth);
        if (identity == null) return ResponseEntity.status(401).build();

        Map<String, Long> byLocation = new LinkedHashMap<>();
        Map<String, Long> onlineByLocation = new LinkedHashMap<>();
        for (AgentLocation l : AgentLocation.values()) {
            byLocation.put(l.name(), agents.countAgents(l, false));
            onlineByLocation.put(l.name(), agents.countAgents(l, true));
        }

        Runtime rt = Runtime.getRuntime();
        return ResponseEntity.ok(new MetricsResponse(
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                new MetricsResponse.Agents(
                        agents.countAgents(null, false),
                        agents.countAgents(null, true),
                        byLocation, onlineByLocation),
                new MetricsResponse.Services(services.count(), services.countPublic()),
                dnsRecords.count(),
                connections.count(),
                sessions.count(),
                new MetricsResponse.Jvm(
                        (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024),
                        rt.maxMemory() / (1024 * 1024),
                        rt.availableProcessors())
        ));
    }

    private SolidIdentity authenticate(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) return null;
        return authService.resolve(auth.substring(7)).orElse(null);
    }
}
