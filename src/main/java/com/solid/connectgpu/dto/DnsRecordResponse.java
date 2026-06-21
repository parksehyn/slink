package com.solid.connectgpu.dto;

public record DnsRecordResponse(
        String id,
        String ownerId,
        String type,
        String name,
        String value,
        int ttl,
        String createdAt,
        String updatedAt
) {}
