package com.solid.connectgpu.port.impl;

import com.solid.connectgpu.port.TunnelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MockTunnelProvider implements TunnelProvider {

    private static final Logger log = LoggerFactory.getLogger(MockTunnelProvider.class);

    @Override
    public String openTunnel(int localPort) {
        String mockUrl = "https://mock-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                         + ".trycloudflare.com";
        log.info("[TUNNEL-MOCK] Would open tunnel: localhost:{} → {} (VM Agent responsibility)", localPort, mockUrl);
        return mockUrl;
    }

    @Override
    public void closeTunnel(String publicUrl) {
        log.info("[TUNNEL-MOCK] Would close tunnel: {}", publicUrl);
    }
}
