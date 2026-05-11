package com.solid.connectgpu.model;

import java.time.Instant;

public class User {
    private final String studentId;
    private final String email;
    private final String apiKeyHash;
    private final Instant createdAt;
    private final Instant expiresAt;

    public User(String studentId, String email, String apiKeyHash) {
        this.studentId = studentId;
        this.email = email;
        this.apiKeyHash = apiKeyHash;
        this.createdAt = Instant.now();
        this.expiresAt = Instant.now().plusSeconds(60L * 60 * 24 * 180); // 6개월
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public String getStudentId() { return studentId; }
    public String getEmail() { return email; }
    public String getApiKeyHash() { return apiKeyHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
