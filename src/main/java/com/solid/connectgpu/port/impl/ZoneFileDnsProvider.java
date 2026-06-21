package com.solid.connectgpu.port.impl;

import com.solid.connectgpu.port.DnsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 실제 CoreDNS zone 파일을 직접 작성하는 {@link DnsProvider} 구현. {@code dns.zone.file} 설정 시 활성화된다.
 * DNS Server VM에 CoreDNS와 같이 두고, 레코드 변경 시 zone 파일을 재작성 + SOA serial을 증가시키면
 * Corefile의 {@code reload} 플러그인이 자동 반영한다(dns-agent 폴링 불필요).
 *
 * <p>레코드 타입은 값 형태로 추론한다: 값이 IPv4면 {@code A}, 아니면 {@code CNAME}.
 * 이렇게 하면 {@link DnsProvider} 인터페이스를 바꾸지 않아 기존 ServiceRegistry 호출과 호환된다.
 */
@Component
@Primary
@ConditionalOnProperty(name = "dns.zone.file")
public class ZoneFileDnsProvider implements DnsProvider {

    private static final Logger log = LoggerFactory.getLogger(ZoneFileDnsProvider.class);
    private static final Pattern IPV4 = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

    private final Path zoneFile;
    private final String zone;
    private final String nsIp;
    private final AtomicLong serial = new AtomicLong(Instant.now().getEpochSecond());

    /** relativeName → value (A=IPv4 / CNAME=대상). 정렬 출력을 위해 TreeMap. */
    private final Map<String, String> entries = new TreeMap<>();

    public ZoneFileDnsProvider(@Value("${dns.zone.file}") String zoneFile,
                               @Value("${dns.zone.name:solid.internal}") String zone,
                               @Value("${dns.zone.ns-ip:127.0.0.1}") String nsIp) {
        this.zoneFile = Path.of(zoneFile);
        this.zone = zone.toLowerCase();
        this.nsIp = nsIp;
        log.info("[DNS-ZONE] enabled file={} zone={} nsIp={}", zoneFile, zone, nsIp);
    }

    @Override
    public synchronized void createRecord(String hostname, String value) {
        entries.put(relativize(hostname), value);
        rewrite();
    }

    @Override
    public synchronized void updateRecord(String hostname, String value) {
        entries.put(relativize(hostname), value);
        rewrite();
    }

    @Override
    public synchronized void deleteRecord(String hostname) {
        entries.remove(relativize(hostname));
        rewrite();
    }

    private String relativize(String hostname) {
        String h = (hostname == null ? "" : hostname).trim().toLowerCase().replaceAll("\\.$", "");
        if (h.isEmpty() || h.equals(zone) || h.equals("@")) return "@";
        if (h.endsWith("." + zone)) return h.substring(0, h.length() - zone.length() - 1);
        return h;
    }

    private void rewrite() {
        long s = serial.incrementAndGet();
        StringBuilder sb = new StringBuilder();
        sb.append("$ORIGIN ").append(zone).append(".\n");
        sb.append("$TTL 3600\n");
        sb.append("@\tIN\tSOA\tns.").append(zone).append(". admin.").append(zone).append(". (\n");
        sb.append("\t\t").append(s).append(" ; serial\n");
        sb.append("\t\t7200 ; refresh\n\t\t3600 ; retry\n\t\t1209600 ; expire\n\t\t3600 ) ; minimum\n");
        sb.append("@\tIN\tNS\tns.").append(zone).append(".\n");
        sb.append("ns\tIN\tA\t").append(nsIp).append("\n");
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String val = e.getValue();
            if (IPV4.matcher(val).matches())
                sb.append(e.getKey()).append("\tIN\tA\t").append(val).append("\n");
            else
                sb.append(e.getKey()).append("\tIN\tCNAME\t").append(val).append("\n");
        }
        try {
            Path tmp = zoneFile.resolveSibling(zoneFile.getFileName() + ".tmp");
            if (zoneFile.getParent() != null) Files.createDirectories(zoneFile.getParent());
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, zoneFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("[DNS-ZONE] wrote {} records, serial={}", entries.size(), s);
        } catch (IOException e) {
            throw new RuntimeException("zone 파일 작성 실패: " + e.getMessage(), e);
        }
    }
}
