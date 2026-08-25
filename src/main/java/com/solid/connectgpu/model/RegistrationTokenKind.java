package com.solid.connectgpu.model;

/**
 * 단기 등록 토큰(rt-)의 용도 구분.
 * <ul>
 *   <li>{@link #OUTBOUND_RESOURCE} — 외부 자원 연결(Colab/Jupyter/HTTP): 외부 에이전트가 자기 터널을 등록.</li>
 *   <li>{@link #INBOUND_DEVICE} — 인바운드 VM 에이전트 헤드리스 등록(디바이스 토큰 발급용, Phase 2).</li>
 * </ul>
 */
public enum RegistrationTokenKind {
    OUTBOUND_RESOURCE,
    INBOUND_DEVICE
}
