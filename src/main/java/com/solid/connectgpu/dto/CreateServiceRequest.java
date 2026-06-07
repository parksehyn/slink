package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.Protocol;
import com.solid.connectgpu.model.ServiceScope;

public record CreateServiceRequest(
        String name,
        String instanceId,
        String privateIp,
        int localPort,
        Protocol protocol,
        ServiceScope scope
) {}
