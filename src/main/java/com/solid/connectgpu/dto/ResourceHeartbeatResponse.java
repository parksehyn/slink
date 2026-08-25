package com.solid.connectgpu.dto;

/** heartbeat 응답. revoked=true면 SOLID측에서 자원이 중지·삭제됐다는 뜻 → 에이전트는 자가 종료한다. */
public record ResourceHeartbeatResponse(boolean revoked) {}
