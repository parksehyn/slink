package com.solid.connectgpu.model;

import java.time.Instant;

public class Session {
    private final String code;
    private final String ngrokHost;
    private final int sshPort;
    private final String otp;
    private final String jupyterToken;
    private final Instant expiresAt;

    public Session(String code, String ngrokHost, int sshPort, String otp, String jupyterToken) {
        this.code = code;
        this.ngrokHost = ngrokHost;
        this.sshPort = sshPort;
        this.otp = otp;
        this.jupyterToken = jupyterToken;
        this.expiresAt = Instant.now().plusSeconds(43200); // 12시간
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public String getCode() { return code; }
    public String getNgrokHost() { return ngrokHost; }
    public int getSshPort() { return sshPort; }
    public String getOtp() { return otp; }
    public String getJupyterToken() { return jupyterToken; }
    public Instant getExpiresAt() { return expiresAt; }
}
