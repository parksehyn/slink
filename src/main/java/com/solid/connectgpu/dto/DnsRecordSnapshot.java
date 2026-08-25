package com.solid.connectgpu.dto;

/**
 * DNS 레코드의 파일 영속용 스냅샷. 도메인 모델({@code DnsRecord})에 Jackson 어노테이션을 달지 않고,
 * 직렬화/역직렬화는 이 단순 record로만 처리한다. 시각은 ISO-8601 문자열로 저장한다.
 */
public record DnsRecordSnapshot(
        String id,
        String ownerId,
        String type,
        String name,
        String value,
        int ttl,
        String vmId,
        String vmName,
        String status,
        String createdAt,
        String updatedAt
) {}
