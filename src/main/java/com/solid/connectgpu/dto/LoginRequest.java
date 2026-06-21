package com.solid.connectgpu.dto;

/** SOLID(CloudStack) 로그인 요청. */
public record LoginRequest(
        String username,   // 학번 또는 계정명
        String password,
        String domain      // 없으면 기본 도메인 사용
) {}
