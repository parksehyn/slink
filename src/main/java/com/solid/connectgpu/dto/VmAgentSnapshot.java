package com.solid.connectgpu.dto;

import com.solid.connectgpu.dto.AgentHeartbeatResponse.TunnelCommand;

import java.util.List;

/**
 * VM Agent 등록 정보의 파일 영속용 스냅샷. {@code at-} 토큰을 보존해 Relay 재시작 후에도
 * 에이전트가 재등록 없이 하트비트할 수 있게 한다(시각은 ISO-8601 문자열).
 * 삭제된 서비스의 미처리 CLOSE_TUNNEL 명령({@code orphanCloses})도 함께 보존해 고아 터널 누수를 막는다.
 */
public record VmAgentSnapshot(
        String agentId,
        String instanceId,
        String ownerId,
        String agentToken,
        String lastHeartbeatAt,
        String registeredAt
) {
    /** {@code orphanCloses} 맵 한 엔트리(키 = "instanceId|ownerId")의 영속 표현. */
    public record OrphanCloseSnapshot(String key, List<TunnelCommand> commands) {}

    /** 저장 파일 한 개에 에이전트 목록과 고아 CLOSE 명령을 함께 담는 래퍼. */
    public record Store(List<VmAgentSnapshot> agents, List<OrphanCloseSnapshot> orphans) {}
}
