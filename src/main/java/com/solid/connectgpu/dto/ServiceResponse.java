package com.solid.connectgpu.dto;

import java.util.List;

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
        String accessPolicy,
        List<String> allowedEmails,
        String createdAt,
        String updatedAt
) {}
