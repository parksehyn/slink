package com.solid.connectgpu.dto;

public record SessionResponse(String code, String ngrokHost, int sshPort, String otp, String jupyterToken, java.time.Instant expiresAt) {}
