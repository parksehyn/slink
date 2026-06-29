package com.solid.connectgpu.dto;

import java.util.List;

/**
 * 인바운드 서비스의 파일 영속용 스냅샷. 도메인 모델({@code ServiceEntry})에 Jackson 어노테이션을
 * 달지 않고, 직렬화/역직렬화는 이 단순 record로만 처리한다(시각은 ISO-8601 문자열, enum은 name()).
 * {@code DnsRecordSnapshot}과 동일한 방식.
 */
public record ServiceEntrySnapshot(
        String id,
        String ownerId,
        String name,
        String instanceId,
        String privateIp,
        int localPort,
        String protocol,
        String scope,
        String status,
        String accessPolicy,
        List<String> allowedEmails,
        String internalHostname,
        String publicUrl,
        String publicExpiresAt,
        String scopeBeforePublish,
        String pendingCommand,
        String agentId,
        String createdAt,
        String updatedAt
) {}
