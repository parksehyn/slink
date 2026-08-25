package com.solid.connectgpu.dto;

/** 현재 토큰의 신원 정보. */
public record AuthMeResponse(
        String account,
        String domain,
        String email
) {}
