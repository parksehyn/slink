package com.solid.connectgpu.dto;

import com.solid.connectgpu.model.DnsRecordType;

public record CreateDnsRecordRequest(
        DnsRecordType type,
        String name,
        String value,
        Integer ttl   // null이면 기본 3600초
) {}
