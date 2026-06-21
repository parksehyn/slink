package com.solid.connectgpu.dto;

public record ConnectionResponse(
        String id,
        String ownerId,
        String name,
        String type,
        String url,
        String token,
        String note,
        String createdAt
) {}
