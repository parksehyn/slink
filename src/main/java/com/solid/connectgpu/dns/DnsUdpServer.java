package com.solid.connectgpu.dns;

import com.solid.connectgpu.model.DnsRecord;
import com.solid.connectgpu.model.DnsRecordType;
import com.solid.connectgpu.service.DnsRecordRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 자체 구현 내부 DNS 서버 (unified-agent-design.md §9 — 외부 서비스·오픈소스 DNS 없이 직접 구현).
 *
 * <p>{@code solid.internal} 존의 <b>권한(authoritative) 서버</b>: UDP 질의를 받아
 * {@link DnsRecordRegistry}를 zone 데이터로 직접 응답한다(A, CNAME+체이닝, NXDOMAIN).
 * 존 밖 질의는 {@code dns.server.upstream} 설정 시 상위 DNS로 <b>스텁 포워딩</b>,
 * 미설정 시 REFUSED — VM들이 이 서버를 유일한 리졸버로 지정해도 일반 인터넷 질의가 동작한다.
 *
 * <p>기본 비활성({@code dns.server.enabled=false}) — Railway 등 UDP 불가 환경에서 안전.
 * DNS Server VM 배포 시 {@code enabled=true, port=53}으로 켠다. 수신 루프는 단일 스레드,
 * 처리·응답은 워커 풀({@code dns.server.workers}) — 포워딩(타임아웃 2초)이 존 내 응답을
 * 막지 않는다.
 *
 * <p>와일드카드: 정확 일치가 없으면 가장 왼쪽 라벨을 {@code *}로 바꿔 재조회한다
 * (예: {@code x.web} → {@code *.web}, {@code x} → {@code *}). 자체 터널 서브도메인
 * 결합용(unified-agent-design.md §9).
 */
@Service
public class DnsUdpServer {

    private static final Logger log = LoggerFactory.getLogger(DnsUdpServer.class);
    private static final int MAX_PACKET = 1024;      // 질의는 통상 512B 이하
    private static final int UPSTREAM_TIMEOUT_MS = 2000;

    private final DnsRecordRegistry registry;
    private final boolean enabled;
    private final int port;
    private final String upstream;
    private final int workerCount;
    private final String zoneSuffix = "." + DnsRecord.ZONE;

    private volatile DatagramSocket socket;
    private Thread loop;
    private ExecutorService workers;

    public DnsUdpServer(DnsRecordRegistry registry,
                        @Value("${dns.server.enabled:false}") boolean enabled,
                        @Value("${dns.server.port:53}") int port,
                        @Value("${dns.server.upstream:}") String upstream,
                        @Value("${dns.server.workers:4}") int workerCount) {
        this.registry = registry;
        this.enabled = enabled;
        this.port = port;
        this.upstream = upstream == null ? "" : upstream.trim();
        this.workerCount = Math.max(1, workerCount);
    }

    @PostConstruct
    void start() throws SocketException {
        if (!enabled) return;
        socket = new DatagramSocket(port);
        workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "dns-udp-worker");
            t.setDaemon(true);
            return t;
        });
        loop = new Thread(this::serve, "dns-udp-server");
        loop.setDaemon(true);
        loop.start();
        log.info("[DNS-SRV] listening on udp/{} (zone={}, upstream={}, workers={})",
                socket.getLocalPort(), DnsRecord.ZONE,
                upstream.isEmpty() ? "none(REFUSED)" : upstream, workerCount);
    }

    @PreDestroy
    void stop() {
        DatagramSocket s = socket;
        if (s != null) s.close();
        if (workers != null) workers.shutdownNow();
    }

    /** 실제 바인딩된 포트 (테스트에서 port=0 사용 시 확인용). 미기동이면 -1. */
    public int getPort() {
        DatagramSocket s = socket;
        return s != null ? s.getLocalPort() : -1;
    }

    private void serve() {
        byte[] buf = new byte[MAX_PACKET];
        while (!socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                // 수신 버퍼는 재사용되므로 워커에 넘기기 전에 복사한다
                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                InetAddress addr = packet.getAddress();
                int port = packet.getPort();
                workers.submit(() -> respond(data, addr, port));
            } catch (SocketException e) {
                break; // stop()에서 close — 정상 종료
            } catch (Exception e) {
                log.warn("[DNS-SRV] receive failed: {}", e.getMessage());
            }
        }
    }

    private void respond(byte[] data, InetAddress addr, int port) {
        try {
            byte[] response = handle(data, data.length);
            if (response != null) {
                socket.send(new DatagramPacket(response, response.length, addr, port));
            }
        } catch (Exception e) {
            log.warn("[DNS-SRV] request handling failed: {}", e.getMessage());
        }
    }

    /** 한 질의 처리. 패키지 프라이빗 — 프로토콜 단위 테스트용. */
    byte[] handle(byte[] data, int length) {
        DnsCodec.Query q;
        try {
            q = DnsCodec.parseQuery(data, length);
        } catch (IllegalArgumentException e) {
            return DnsCodec.encodeErrorFor(data, length, DnsCodec.RCODE_FORMERR);
        }
        if (q.opcode() != 0) // QUERY만 지원 (IQUERY/STATUS/UPDATE 등은 NOTIMP)
            return DnsCodec.encodeResponse(q, DnsCodec.RCODE_NOTIMP, List.of());
        if (q.qclass() != DnsCodec.CLASS_IN)
            return DnsCodec.encodeResponse(q, DnsCodec.RCODE_REFUSED, List.of());

        if (inZone(q.qname())) return answerFromZone(q);
        return forwardUpstream(data, length, q);
    }

    private boolean inZone(String qname) {
        return qname.equals(DnsRecord.ZONE) || qname.endsWith(zoneSuffix);
    }

    /** 존 내 질의 응답 — 레지스트리가 zone 데이터. */
    private byte[] answerFromZone(DnsCodec.Query q) {
        String label = q.qname().equals(DnsRecord.ZONE)
                ? "@"
                : q.qname().substring(0, q.qname().length() - zoneSuffix.length());
        Optional<DnsRecord> found = registry.findByName(label);
        if (found.isEmpty() && !label.equals("@") && !label.startsWith("*")) {
            // 와일드카드 합성: 가장 왼쪽 라벨을 *로 (x.web → *.web, x → *)
            String wildcard = label.contains(".")
                    ? "*" + label.substring(label.indexOf('.'))
                    : "*";
            found = registry.findByName(wildcard);
        }
        if (found.isEmpty())
            return DnsCodec.encodeResponse(q, DnsCodec.RCODE_NXDOMAIN, List.of());

        DnsRecord r = found.get();
        List<DnsCodec.Answer> answers = new ArrayList<>(2);
        boolean wantA = q.qtype() == DnsCodec.TYPE_A || q.qtype() == DnsCodec.TYPE_ANY;
        boolean wantCname = q.qtype() == DnsCodec.TYPE_CNAME || q.qtype() == DnsCodec.TYPE_ANY;

        if (r.getType() == DnsRecordType.A) {
            if (wantA) answers.add(DnsCodec.Answer.a(q.qname(), r.getTtl(), r.getValue()));
            // A 레코드에 CNAME 질의 등 타입 불일치 → NOERROR + 빈 answer (NODATA)
        } else { // CNAME
            String targetFqdn = canonicalTarget(r.getValue());
            if (wantCname || wantA) {
                answers.add(DnsCodec.Answer.cname(q.qname(), r.getTtl(), targetFqdn));
                // 대상이 존 내부 A 레코드면 체이닝해서 한 번에 답한다 (표준 리졸버 동작 절약)
                if (wantA && targetFqdn.endsWith(zoneSuffix)) {
                    String targetLabel = targetFqdn.substring(0, targetFqdn.length() - zoneSuffix.length());
                    registry.findByName(targetLabel)
                            .filter(t -> t.getType() == DnsRecordType.A)
                            .ifPresent(t -> answers.add(
                                    DnsCodec.Answer.a(targetFqdn, t.getTtl(), t.getValue())));
                }
            }
        }
        return DnsCodec.encodeResponse(q, DnsCodec.RCODE_NOERROR, answers);
    }

    /** CNAME 대상 정규화: 짧은 라벨이면 존 FQDN으로, 점이 있는 이름(FQDN/외부)은 그대로. */
    private String canonicalTarget(String value) {
        String v = value.toLowerCase().replaceAll("\\.$", "");
        return v.contains(".") ? v : v + zoneSuffix;
    }

    /**
     * 존 밖 질의 스텁 포워딩 — 원본 패킷을 상위 DNS로 그대로 중계하고 응답을 돌려준다
     * (ID 동일하므로 재작성 불필요). 상위 미설정 시 REFUSED.
     */
    private byte[] forwardUpstream(byte[] data, int length, DnsCodec.Query q) {
        if (upstream.isEmpty())
            return DnsCodec.encodeResponse(q, DnsCodec.RCODE_REFUSED, List.of());
        try (DatagramSocket up = new DatagramSocket()) {
            up.setSoTimeout(UPSTREAM_TIMEOUT_MS);
            up.send(new DatagramPacket(data, length, InetAddress.getByName(upstream), 53));
            byte[] buf = new byte[4096];
            DatagramPacket resp = new DatagramPacket(buf, buf.length);
            up.receive(resp);
            byte[] out = new byte[resp.getLength()];
            System.arraycopy(resp.getData(), 0, out, 0, resp.getLength());
            return out;
        } catch (Exception e) {
            log.warn("[DNS-SRV] upstream {} failed for {}: {}", upstream, q.qname(), e.getMessage());
            return DnsCodec.encodeResponse(q, DnsCodec.RCODE_SERVFAIL, List.of());
        }
    }
}
