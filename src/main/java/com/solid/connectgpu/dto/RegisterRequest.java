package com.solid.connectgpu.dto;

public record RegisterRequest(String ngrokHost, int sshPort, String otp, String jupyterToken) {}
