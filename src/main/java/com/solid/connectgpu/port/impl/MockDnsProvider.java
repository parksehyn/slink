package com.solid.connectgpu.port.impl;

import com.solid.connectgpu.port.DnsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 기본 DNS 구현(로그만). {@code dns.zone.file} 설정 시 {@link ZoneFileDnsProvider}로 대체된다. */
@Component
@ConditionalOnProperty(name = "dns.zone.file", matchIfMissing = true)
public class MockDnsProvider implements DnsProvider {

    private static final Logger log = LoggerFactory.getLogger(MockDnsProvider.class);

    @Override
    public void createRecord(String hostname, String ip) {
        log.info("[DNS-MOCK] Would create: {} → {}", hostname, ip);
    }

    @Override
    public void updateRecord(String hostname, String ip) {
        log.info("[DNS-MOCK] Would update: {} → {}", hostname, ip);
    }

    @Override
    public void deleteRecord(String hostname) {
        log.info("[DNS-MOCK] Would delete: {}", hostname);
    }
}
