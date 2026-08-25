package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.ResourceType;

/** 포털(SOLID 인증)이 외부 자원 에이전트용 단기 등록 토큰을 발급할 때의 요청. */
public record IssueRegistrationTokenRequest(
        ResourceType resourceType,
        String name,
        Long ttlMinutes   // 선택 (미지정 시 서버 기본값)
) {}
