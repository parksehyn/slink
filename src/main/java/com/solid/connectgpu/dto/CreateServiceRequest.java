package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.AccessPolicy;
import com.solid.connectgpu.model.Protocol;
import com.solid.connectgpu.model.ServiceScope;

import java.util.List;

public record CreateServiceRequest(
        String name,
        String instanceId,
        String privateIp,
        int localPort,
        Protocol protocol,
        ServiceScope scope,
        AccessPolicy accessPolicy,   // null이면 DKU_INTERNAL (기본)
        List<String> allowedEmails   // null이면 빈 목록
) {}
