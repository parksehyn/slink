package com.solid.connectgpu.dns;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RFC 1035 DNS 메시지 최소 코덱 — 외부 라이브러리 없이 직접 구현
 * (unified-agent-design.md §2.2: dnsmasq 등 오픈소스도 쓰지 않는다).
 *
 * <p>권한 서버에 필요한 범위만 지원한다: 질문 1개 파싱(질의는 압축을 쓰지 않음),
 * 응답 인코딩(answer name은 질문(오프셋 12)을 가리키는 포인터 {@code 0xC00C}로 압축).
 * EDNS/TCP/DNSSEC은 범위 밖 — 우리 응답은 수십 바이트라 UDP 512 한도로 충분하다.
 */
public final class DnsCodec {

    public static final int TYPE_A = 1;
    public static final int TYPE_CNAME = 5;
    public static final int TYPE_ANY = 255;
    public static final int CLASS_IN = 1;

    public static final int RCODE_NOERROR = 0;
    public static final int RCODE_FORMERR = 1;
    public static final int RCODE_SERVFAIL = 2;
    public static final int RCODE_NXDOMAIN = 3;
    public static final int RCODE_NOTIMP = 4;
    public static final int RCODE_REFUSED = 5;

    private DnsCodec() {}

    /** 파싱된 질의 — 응답 생성에 필요한 최소 정보. */
    public record Query(int id, int opcode, boolean recursionDesired,
                        String qname, int qtype, int qclass) {}

    /** 응답 RR 하나 (name은 FQDN, rdata는 타입별 인코딩 전 원시 값). */
    public record Answer(String name, int type, long ttl, byte[] rdata) {
        public static Answer a(String name, long ttl, String ipv4) {
            String[] p = ipv4.split("\\.");
            return new Answer(name, TYPE_A, ttl, new byte[]{
                    (byte) Integer.parseInt(p[0]), (byte) Integer.parseInt(p[1]),
                    (byte) Integer.parseInt(p[2]), (byte) Integer.parseInt(p[3])});
        }

        public static Answer cname(String name, long ttl, String targetFqdn) {
            return new Answer(name, TYPE_CNAME, ttl, encodeName(targetFqdn));
        }
    }

    /** 질의 파싱. 형식 오류 시 IllegalArgumentException (호출자가 FORMERR로 응답). */
    public static Query parseQuery(byte[] data, int length) {
        if (length < 12) throw new IllegalArgumentException("message too short");
        int id = u16(data, 0);
        int flags = u16(data, 2);
        boolean qr = (flags & 0x8000) != 0;
        int opcode = (flags >> 11) & 0xF;
        boolean rd = (flags & 0x0100) != 0;
        int qdcount = u16(data, 4);
        if (qr) throw new IllegalArgumentException("not a query");
        if (qdcount < 1) throw new IllegalArgumentException("no question");

        StringBuilder qname = new StringBuilder();
        int pos = 12;
        while (true) {
            if (pos >= length) throw new IllegalArgumentException("truncated qname");
            int len = data[pos] & 0xFF;
            if ((len & 0xC0) != 0) throw new IllegalArgumentException("compressed qname in query");
            pos++;
            if (len == 0) break;
            if (pos + len > length) throw new IllegalArgumentException("truncated label");
            if (qname.length() > 0) qname.append('.');
            qname.append(new String(data, pos, len, StandardCharsets.US_ASCII));
            pos += len;
        }
        if (pos + 4 > length) throw new IllegalArgumentException("truncated question");
        int qtype = u16(data, pos);
        int qclass = u16(data, pos + 2);
        return new Query(id, opcode, rd, qname.toString().toLowerCase(), qtype, qclass);
    }

    /** 응답 인코딩. answers는 순서대로 answer 섹션에 들어간다(빈 리스트 = NODATA/에러 응답). */
    public static byte[] encodeResponse(Query q, int rcode, List<Answer> answers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        // Header
        writeU16(out, q.id());
        int flags = 0x8000                       // QR=response
                | (q.opcode() << 11)
                | 0x0400                         // AA=authoritative
                | (q.recursionDesired() ? 0x0100 : 0)
                | (rcode & 0xF);                 // RA=0: 우리는 리커시브 리졸버가 아니다
        writeU16(out, flags);
        writeU16(out, 1);                        // QDCOUNT
        writeU16(out, answers.size());           // ANCOUNT
        writeU16(out, 0);                        // NSCOUNT
        writeU16(out, 0);                        // ARCOUNT
        // Question (질의 그대로 재인코딩 — 질의는 비압축이므로 동일 바이트)
        out.writeBytes(encodeName(q.qname()));
        writeU16(out, q.qtype());
        writeU16(out, q.qclass());
        // Answers
        for (Answer a : answers) {
            if (a.name().equalsIgnoreCase(q.qname())) {
                writeU16(out, 0xC00C);           // 질문 name(오프셋 12) 포인터
            } else {
                out.writeBytes(encodeName(a.name()));
            }
            writeU16(out, a.type());
            writeU16(out, CLASS_IN);
            writeU32(out, a.ttl());
            writeU16(out, a.rdata().length);
            out.writeBytes(a.rdata());
        }
        return out.toByteArray();
    }

    /** 파싱 불가 패킷에 대한 최선의 에러 응답 (ID만 베껴 FORMERR). 12바이트 미만이면 null. */
    public static byte[] encodeErrorFor(byte[] data, int length, int rcode) {
        if (length < 12) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(12);
        writeU16(out, u16(data, 0));
        writeU16(out, 0x8000 | (rcode & 0xF));
        writeU16(out, 0); writeU16(out, 0); writeU16(out, 0); writeU16(out, 0);
        return out.toByteArray();
    }

    /** 도메인 이름 → 라벨 길이 전치 형식 (비압축). */
    static byte[] encodeName(String fqdn) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fqdn.length() + 2);
        for (String label : fqdn.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.writeBytes(bytes);
        }
        out.write(0);
        return out.toByteArray();
    }

    private static int u16(byte[] d, int off) {
        return ((d[off] & 0xFF) << 8) | (d[off + 1] & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, long v) {
        out.write((int) ((v >> 24) & 0xFF));
        out.write((int) ((v >> 16) & 0xFF));
        out.write((int) ((v >> 8) & 0xFF));
        out.write((int) (v & 0xFF));
    }
}
