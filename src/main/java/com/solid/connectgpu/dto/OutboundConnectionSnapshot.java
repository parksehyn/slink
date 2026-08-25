package com.solid.connectgpu.dto;

/**
 * 아웃바운드 외부 연결의 파일 영속용 스냅샷. 직렬화/역직렬화는 이 단순 record로만 처리한다
 * (시각은 ISO-8601 문자열, enum은 name()). {@code DnsRecordSnapshot}과 동일한 방식.
 */
public record OutboundConnectionSnapshot(
        String id,
        String ownerId,
        String name,
        String type,
        String url,
        String token,
        String note,
        String createdAt
) {}
