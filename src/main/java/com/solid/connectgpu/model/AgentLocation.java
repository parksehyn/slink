package com.solid.connectgpu.model;

/**
 * Agent가 실행되는 위치. 기존 "인바운드/아웃바운드" 구분을 대체하는 속성이다
 * (docs/unified-agent-design.md §3.2 — 위치는 별개 기능이 아니라 Agent의 속성).
 */
public enum AgentLocation {
    /** SOLID VM 위에서 실행 — 기존 인바운드(서비스 외부 공개). instanceId를 가진다. */
    SOLID_VM,
    /** Google Colab 런타임 — 기존 아웃바운드의 Colab GPU 케이스. */
    COLAB,
    /** 그 외 외부 환경(연구실 PC, 외부 서버 등) — 기존 아웃바운드 일반 케이스. */
    EXTERNAL;

    /** SOLID 밖(NAT 뒤 외부 환경)에서 도는 Agent인가. */
    public boolean isExternal() {
        return this != SOLID_VM;
    }
}
