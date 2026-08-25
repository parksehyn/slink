package com.solid.connectgpu.dto;

/**
 * 에이전트의 자원 생명주기 보고.
 * <ul>
 *   <li>RESOURCE_READY  — 터널 개통(publicUrl 제공). 최초 보고 + URL 변경 재접속 겸용(in-place 갱신).</li>
 *   <li>RESOURCE_STOPPED — 정상 종료</li>
 *   <li>RESOURCE_FAILED  — 개통 실패(reason)</li>
 * </ul>
 */
public record ResourceReportRequest(
        String event,
        String publicUrl,     // RESOURCE_READY
        String serviceToken,  // 선택
        String expiresAt,     // 선택 (ISO-8601)
        String reason         // RESOURCE_FAILED
) {}
