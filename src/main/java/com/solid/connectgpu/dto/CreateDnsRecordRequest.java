package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.DnsRecordType;

/**
 * DNS 레코드 생성 요청.
 * <ul>
 *   <li>{@code A}     — {@code vmId} 필수. 값(사설 IP)은 서버가 CloudStack에서 조회·검증해 채운다(명세서 §5.2).</li>
 *   <li>{@code CNAME} — {@code value}(대상 호스트) 사용. {@code vmId}는 무시.</li>
 * </ul>
 */
public record CreateDnsRecordRequest(
        DnsRecordType type,
        String name,      // 짧은 호스트 라벨 (예: web)
        String vmId,      // A 레코드: 대상 VM instanceId
        String value,     // CNAME 대상 호스트 (A에서는 무시)
        Integer ttl       // null이면 기본 3600초
) {}
