package com.solid.connectgpu.dto;

public record DnsRecordResponse(
        String id,
        String ownerId,
        String type,
        String name,
        String fqdn,
        String value,
        int ttl,
        String vmId,
        String vmName,
        String status,
        String createdAt,
        String updatedAt
) {}
