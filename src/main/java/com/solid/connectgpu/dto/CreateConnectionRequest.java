package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.ConnectionType;

public record CreateConnectionRequest(
        String name,
        ConnectionType type,
        String url,
        String token,   // 선택
        String note     // 선택
) {}
