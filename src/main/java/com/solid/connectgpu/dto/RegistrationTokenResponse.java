package com.solid.connectgpu.dto;

/** 발급된 단기 등록 토큰(rt-)과 메타. 포털이 이걸로 Colab 셀 스니펫/디바이스 enroll 명령을 만든다. */
public record RegistrationTokenResponse(
        String token,         // rt-...
        String resourceType,
        String name,
        String expiresAt
) {}
