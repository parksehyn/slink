package com.solid.connectgpu;

import com.jayway.jsonpath.JsonPath;
import com.solid.connectgpu.dns.DnsUdpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자체 구현 DNS 응답기 검증 — 실제 UDP 소켓으로 RFC 1035 질의를 보내 응답을 파싱한다.
 * 레코드는 API로 만들고(레지스트리 = zone 데이터), 응답기가 그것을 직접 서빙하는지 확인.
 */
@SpringBootTest(properties = {"dns.server.enabled=true", "dns.server.port=0"})
class DnsUdpServerTest {

    private static final int TYPE_A = 1;
    private static final int TYPE_CNAME = 5;

    @Autowired WebApplicationContext context;
    @Autowired DnsUdpServer server;

    MockMvc mvc;
    String token;
    String vmId;

    @BeforeEach
    void setup() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long ts = System.nanoTime();
        String username = String.valueOf(10_000_000L + Math.abs(ts % 80_000_000L));
        token = JsonPath.read(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pw\"}"))
                .andReturn().getResponse().getContentAsString(), "$.token");
        vmId = JsonPath.read(mvc.perform(get("/api/vms").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(), "$[0].instanceId");
    }

    private String createA(String name) throws Exception {
        String json = mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"" + name + "\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.value"); // 사설 IP
    }

    // ── 최소 DNS 클라이언트 (테스트 전용) ─────────────────────────────────

    private byte[] buildQuery(String fqdn, int qtype) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12); out.write(0x34);          // ID
        out.write(0x01); out.write(0x00);          // flags: RD
        out.write(0); out.write(1);                // QDCOUNT=1
        out.write(0); out.write(0);
        out.write(0); out.write(0);
        out.write(0); out.write(0);
        for (String label : fqdn.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            out.write(b.length);
            out.writeBytes(b);
        }
        out.write(0);
        out.write(0); out.write(qtype);
        out.write(0); out.write(1);                // IN
        return out.toByteArray();
    }

    private byte[] resolve(String fqdn, int qtype) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(3000);
            byte[] q = buildQuery(fqdn, qtype);
            s.send(new DatagramPacket(q, q.length, InetAddress.getLoopbackAddress(), server.getPort()));
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            s.receive(p);
            return Arrays.copyOf(p.getData(), p.getLength());
        }
    }

    private static int rcode(byte[] resp)   { return resp[3] & 0xF; }
    private static int ancount(byte[] resp) { return ((resp[6] & 0xFF) << 8) | (resp[7] & 0xFF); }
    private static String lastIp(byte[] resp) {
        int n = resp.length;
        return (resp[n - 4] & 0xFF) + "." + (resp[n - 3] & 0xFF) + "."
                + (resp[n - 2] & 0xFF) + "." + (resp[n - 1] & 0xFF);
    }

    // ── 테스트 ───────────────────────────────────────────────────────────

    @Test
    void aRecord_resolvesToVmPrivateIp() throws Exception {
        String name = "udp-a-" + (System.nanoTime() % 1_000_000);
        String ip = createA(name);

        byte[] resp = resolve(name + ".solid.internal", TYPE_A);
        assertThat(rcode(resp)).isZero();
        assertThat(ancount(resp)).isEqualTo(1);
        assertThat(lastIp(resp)).isEqualTo(ip);
        assertThat(resp[0]).isEqualTo((byte) 0x12); // ID echo
        assertThat(resp[1]).isEqualTo((byte) 0x34);
    }

    @Test
    void unknownName_inZone_returnsNxdomain() throws Exception {
        byte[] resp = resolve("udp-nope-" + (System.nanoTime() % 1_000_000) + ".solid.internal", TYPE_A);
        assertThat(rcode(resp)).isEqualTo(3); // NXDOMAIN
        assertThat(ancount(resp)).isZero();
    }

    @Test
    void cname_chasesToARecord_inOneAnswer() throws Exception {
        String target = "udp-tgt-" + (System.nanoTime() % 1_000_000);
        String ip = createA(target);
        String alias = "udp-alias-" + (System.nanoTime() % 1_000_000);
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CNAME\",\"name\":\"" + alias
                                + "\",\"value\":\"" + target + ".solid.internal\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated());

        byte[] resp = resolve(alias + ".solid.internal", TYPE_A);
        assertThat(rcode(resp)).isZero();
        assertThat(ancount(resp)).isEqualTo(2); // CNAME + 체이닝된 A
        assertThat(lastIp(resp)).isEqualTo(ip);
    }

    @Test
    void outOfZone_withoutUpstream_isRefused() throws Exception {
        byte[] resp = resolve("example.com", TYPE_A);
        assertThat(rcode(resp)).isEqualTo(5); // REFUSED
    }

    @Test
    void wildcardRecord_matchesAnySubLabel() throws Exception {
        String base = "wc-" + (System.nanoTime() % 1_000_000);
        String ip = createA("*." + base);   // *.wc-N.solid.internal

        byte[] resp = resolve("anything." + base + ".solid.internal", TYPE_A);
        assertThat(rcode(resp)).isZero();
        assertThat(ancount(resp)).isEqualTo(1);
        assertThat(lastIp(resp)).isEqualTo(ip);
    }

    @Test
    void rootWildcard_creationIsRejected() throws Exception {
        mvc.perform(post("/api/dns/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"A\",\"name\":\"*\",\"vmId\":\"" + vmId + "\"}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void typeMismatch_returnsNodata() throws Exception {
        String name = "udp-nodata-" + (System.nanoTime() % 1_000_000);
        createA(name);
        byte[] resp = resolve(name + ".solid.internal", TYPE_CNAME);
        assertThat(rcode(resp)).isZero();   // NOERROR
        assertThat(ancount(resp)).isZero(); // but no data
    }
}
