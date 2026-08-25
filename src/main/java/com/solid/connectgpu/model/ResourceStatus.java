package com.solid.connectgpu.model;

/**
 * 외부 자원 상태. 저장되는 base 상태는 {@link #PENDING}/{@link #ACTIVE}/{@link #STOPPED}이며,
 * {@link #STALE}/{@link #EXPIRED}는 heartbeat·만료시각으로 읽기 시점에 계산된다
 * ({@code AgentService.effectiveStatus()}).
 */
public enum ResourceStatus {
    PENDING,   // 에이전트 등록됨, 아직 publicUrl 미보고
    ACTIVE,    // publicUrl 있고 heartbeat 정상(liveness 윈도우 내)
    STALE,     // publicUrl 있으나 heartbeat 끊김(만료 전)
    EXPIRED,   // expiresAt 경과
    STOPPED    // 에이전트가 중지 보고 / 소유자 삭제
}
