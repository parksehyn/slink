package com.solid.connectgpu.dto;

/**
 * 포털용 외부 자원 표현. {@code status}는 계산된 effectiveStatus.
 * {@code serviceToken}은 상세 조회(소유자)에서만 채우고 목록에서는 null(마스킹)이다.
 */
public record ExternalResourceResponse(
        String id,
        String ownerId,
        String resourceType,
        String name,
        String status,
        String publicUrl,
        String serviceToken,
        String expiresAt,
        String lastHeartbeatAt,
        String createdAt,
        String updatedAt
) {}
