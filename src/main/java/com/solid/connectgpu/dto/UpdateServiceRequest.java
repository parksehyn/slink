package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.AccessPolicy;
import com.solid.connectgpu.model.ServiceScope;

import java.util.List;

public record UpdateServiceRequest(
        String name,                 // null이면 변경 없음
        ServiceScope scope,          // null이면 변경 없음
        AccessPolicy accessPolicy,   // null이면 변경 없음
        List<String> allowedEmails   // null이면 변경 없음
) {}
