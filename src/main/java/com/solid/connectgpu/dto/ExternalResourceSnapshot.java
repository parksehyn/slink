package com.solid.connectgpu.dto;

/** 외부 자원 영속 스냅샷(resource.store.file). enum은 name()·시각은 ISO-8601 문자열. */
public record ExternalResourceSnapshot(
        String id,
        String ownerId,
        String resourceType,
        String name,
        String status,
        String publicUrl,
        String serviceToken,
        String expiresAt,        // nullable
        String agentToken,
        String lastHeartbeatAt,
        String createdAt,
        String updatedAt
) {}
