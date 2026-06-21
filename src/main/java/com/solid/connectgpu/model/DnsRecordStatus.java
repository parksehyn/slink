package com.solid.connectgpu.model;

/**
 * DNS 레코드의 CoreDNS 반영 상태 (명세서 §8).
 * <ul>
 *   <li>{@code PENDING_SYNC} — 저장소에는 저장됐으나 CoreDNS 반영 대기/진행 중</li>
 *   <li>{@code ACTIVE}       — zone 파일 반영 완료</li>
 *   <li>{@code FAILED}       — zone 반영 실패</li>
 *   <li>{@code DELETED}      — 삭제됨(현재는 하드 삭제라 영속되지 않음)</li>
 * </ul>
 */
public enum DnsRecordStatus {
    PENDING_SYNC,
    ACTIVE,
    FAILED,
    DELETED
}
