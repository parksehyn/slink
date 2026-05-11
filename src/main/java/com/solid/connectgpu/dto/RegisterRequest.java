package com.solid.connectgpu.dto;

public record RegisterRequest(String owner, String ngrokHost, int sshPort, String otp, String jupyterToken) {}
