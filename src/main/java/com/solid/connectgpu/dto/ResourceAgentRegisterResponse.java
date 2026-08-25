package com.solid.connectgpu.dto;

/** 자원 등록 결과. 이후 에이전트는 agentToken(rat-)으로 heartbeat/report 한다. */
public record ResourceAgentRegisterResponse(
        String resourceId,
        String agentToken,    // rat-...
        String resourceType,
        String name
) {}
