package com.solid.connectgpu.dto;

import java.util.Map;

/**
 * Relay 실시간 지표 (unified-agent-design.md §8.1). 포털 지표 탭의 데이터 소스.
 * 개인 데이터 없는 시스템 전역 집계만 담는다.
 */
public record MetricsResponse(
        long uptimeSeconds,
        Agents agents,
        Services services,
        long dnsRecords,
        long outboundConnections,
        long colabSessions,
        Jvm jvm
) {
    /** Agent 집계 — 위치별 분포 포함 (SOLID_VM/COLAB/EXTERNAL). */
    public record Agents(long total, long online,
                         Map<String, Long> byLocation,
                         Map<String, Long> onlineByLocation) {}

    /** 인바운드 서비스 집계. */
    public record Services(long total, long published) {}

    public record Jvm(long usedMemoryMb, long maxMemoryMb, int availableProcessors) {}
}
