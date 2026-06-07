package com.solid.connectgpu.port;

public interface TunnelProvider {
    // Tunnel target is always localhost on the VM Agent side; privateIp must NOT be passed.
    // Real VM ownership validation (CloudStack / VM Agent) is required before activating a real impl.
    String openTunnel(int localPort);
    void closeTunnel(String publicUrl);
}
