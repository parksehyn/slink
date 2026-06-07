package com.solid.connectgpu.dto;

import java.util.List;

public record AgentHeartbeatResponse(List<TunnelCommand> commands) {

    public record TunnelCommand(
            String serviceId,
            String action,      // "OPEN_TUNNEL" or "CLOSE_TUNNEL"
            int    localPort,
            String publicUrl    // present for CLOSE_TUNNEL (so agent knows which tunnel to close)
    ) {}
}
