#!/usr/bin/env python3
"""
slink dns-agent — Relay의 /api/dns/records 를 폴링해 CoreDNS 존 파일을 갱신한다.
포털에서 추가/수정/삭제한 DNS 레코드가 실제 CoreDNS에 반영되게 하는 '다리'.
표준 라이브러리만 사용 (pip 불필요) → 빈 VM에서도 바로 실행.

환경변수:
  SLINK_RELAY_URL   Relay base URL (예: http://10.0.10.20:8081)   [필수]
  SLINK_API_KEY     사용자 API Key (sk-dku-...)                    [필수]
  SLINK_ZONE        존 이름            (기본 solid.internal)
  SLINK_ZONE_FILE   존 파일 경로        (기본 /etc/coredns/db.solid.internal)
  SLINK_NS_IP       ns 레코드 IP = 이 DNS VM 자신 (기본 127.0.0.1)
  SLINK_INTERVAL    폴링 간격 초        (기본 10)

동작: GET /api/dns/records → 존 파일 렌더 → 내용이 바뀌었으면 SOA serial++ 후 원자적 교체.
      CoreDNS file 플러그인이 serial 변경을 감지해 자동 reload.
"""
import json, os, re, sys, time, urllib.request, urllib.error

RELAY    = os.environ.get("SLINK_RELAY_URL", "").rstrip("/")
KEY      = os.environ.get("SLINK_API_KEY", "")
ZONE     = os.environ.get("SLINK_ZONE", "solid.internal")
ZFILE    = os.environ.get("SLINK_ZONE_FILE", "/etc/coredns/db.solid.internal")
NS_IP    = os.environ.get("SLINK_NS_IP", "127.0.0.1")
INTERVAL = int(os.environ.get("SLINK_INTERVAL", "10"))

if not RELAY or not KEY:
    sys.exit("SLINK_RELAY_URL 과 SLINK_API_KEY 환경변수가 필요합니다.")


def fetch_records():
    req = urllib.request.Request(RELAY + "/api/dns/records",
                                 headers={"Authorization": "Bearer " + KEY})
    with urllib.request.urlopen(req, timeout=8) as r:
        return json.load(r)


def to_host(fqdn):
    if fqdn == ZONE:
        return "@"
    suffix = "." + ZONE
    return fqdn[:-len(suffix)] if fqdn.endswith(suffix) else fqdn


def render(records, serial):
    lines = [
        "; 자동 생성 — slink dns-agent. 직접 편집하지 마세요.",
        "$ORIGIN %s." % ZONE,
        "$TTL 3600",
        "@\tIN\tSOA\tns.%s. admin.%s. ( %d 7200 3600 1209600 3600 )" % (ZONE, ZONE, serial),
        "@\tIN\tNS\tns.%s." % ZONE,
        "ns\tIN\tA\t%s" % NS_IP,
    ]
    for rec in records:
        host = to_host(rec["name"])
        ttl = int(rec.get("ttl", 3600))
        rtype = rec["type"]
        value = rec["value"]
        if rtype == "CNAME" and not value.endswith("."):
            value += "."          # CNAME 대상은 FQDN 점 보장
        lines.append("%s\t%d\tIN\t%s\t%s" % (host, ttl, rtype, value))
    return "\n".join(lines) + "\n"


def read_serial():
    try:
        with open(ZFILE) as f:
            m = re.search(r"SOA.*\(\s*(\d+)", f.read())
            return int(m.group(1)) if m else 0
    except FileNotFoundError:
        return 0


def strip_serial(text):
    # serial 줄만 다른 경우는 '변경 없음'으로 취급하기 위한 비교용 정규화
    return re.sub(r"\(\s*\d+", "( SERIAL", text)


def main():
    last_body = None
    print("[dns-agent] %s 폴링 시작 → %s (%ds 간격)" % (RELAY, ZFILE, INTERVAL))
    while True:
        try:
            records = fetch_records()
            serial = max(read_serial() + 1, int(time.time()))
            rendered = render(records, serial)
            if strip_serial(rendered) != last_body:
                tmp = ZFILE + ".tmp"
                with open(tmp, "w") as f:
                    f.write(rendered)
                os.replace(tmp, ZFILE)          # 원자적 교체
                last_body = strip_serial(rendered)
                print("[dns-agent] 갱신: %d개 레코드, serial=%d" % (len(records), serial))
        except urllib.error.HTTPError as e:
            print("[dns-agent] HTTP %s — API Key/URL 확인" % e.code)
        except Exception as e:
            print("[dns-agent] 오류: %s" % e)
        time.sleep(INTERVAL)


if __name__ == "__main__":
    main()
