package com.solid.connectgpu.dto;

public record ServiceResponse(
        String id,
        String ownerId,
        String name,
        String instanceId,
        String privateIp,
        int    localPort,
        String protocol,
        String scope,
        String status,
        String pendingCommand,
        String internalHostname,
        String publicUrl,
        String publicExpiresAt,
        String createdAt,
        String updatedAt
) {}
