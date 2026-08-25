package com.solid.connectgpu.dto;

/** 로그인 성공 시 발급되는 slink 세션 토큰 + 신원 요약. (sessionkey는 서버 보관, 미노출) */
public record AuthTokenResponse(
        String token,
        String account,
        String domain,
        String email
) {}
