package com.solid.connectgpu.dto;

/** 포털(SOLID 인증)이 특정 VM의 인바운드 에이전트 헤드리스 등록용 단기 토큰(rt-)을 발급할 때의 요청. */
public record DeviceTokenRequest(String instanceId) {}
