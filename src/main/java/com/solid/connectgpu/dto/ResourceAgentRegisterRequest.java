package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.ResourceType;

/**
 * 외부 에이전트가 등록 토큰(rt-)으로 자원을 등록할 때의 요청.
 * ownerType/resourceType/name은 서버가 grant에서 신뢰값으로 채우므로 여기 값은 참고용이다.
 * 등록과 동시에 publicUrl을 보고할 수도 있다(선택).
 */
public record ResourceAgentRegisterRequest(
        ResourceType resourceType,   // 참고용 (서버는 grant 값을 사용)
        String name,                 // 참고용 (서버는 grant 값을 사용)
        String publicUrl,            // 선택 (등록 즉시 보고)
        String serviceToken,         // 선택 (jupyter token 등)
        String expiresAt             // 선택 (ISO-8601)
) {}
