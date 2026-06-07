package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.ServiceScope;

public record UpdateServiceRequest(
        String name,          // null이면 변경 없음
        ServiceScope scope    // null이면 변경 없음
) {}
