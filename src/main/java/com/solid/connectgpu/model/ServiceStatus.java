package com.solid.connectgpu.model;

public enum ServiceStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE,
    PENDING   // tunnel open requested; waiting for VM Agent to confirm
}
