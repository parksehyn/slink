package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.DnsRecordType;

public record UpdateDnsRecordRequest(
        DnsRecordType type,   // null이면 변경 없음
        String name,          // null이면 변경 없음
        String value,         // null이면 변경 없음
        Integer ttl           // null이면 변경 없음
) {}
