package com.solid.connectgpu.dto;

public record AgentReportRequest(
        String serviceId,
        String event,       // "TUNNEL_READY" | "TUNNEL_STOPPED" | "TUNNEL_FAILED"
        String publicUrl,   // set for TUNNEL_READY
        String reason       // set for TUNNEL_FAILED
) {}
