# Relay를 SOLID VM에 배포 (deploy/relay)

slink Relay/Registry(터널링·서비스 API)를 SOLID VM에 systemd로 띄우는 골격이다.
DNS 서버(`deploy/dns`)와 **같은 jar**를 쓰며 환경변수로 역할이 갈린다.

> **⚠ 이건 relay를 DNS와 다른 VM으로 분리할 때(2-VM)용 대안이다.**
> **한 VM에 다 올리는 경우(권장)** 는 이 디렉터리 대신 [`deploy/dns/slink-dns.service`](../dns/slink-dns.service)
> 하나로 DNS+터널링+포털을 전부 서빙한다(`SERVICE_STORE_FILE` 등 이미 포함). 별도 relay 서비스 불필요.
> relay·DNS는 같은 jar라 한 프로세스(:8081)가 양쪽 API를 모두 처리하기 때문이다.

> 설계·도달성 분석: [`docs/relay-on-vm.md`](../../docs/relay-on-vm.md)

## 토폴로지 (VM 2개 분리)

| VM | 역할 | 사설 IP | 유닛 |
|----|------|---------|------|
| **SOLID-R** | Relay/Registry (터널링·서비스 API + 포털) | `10.0.10.89:8081` | `deploy/relay/slink-relay.service` |
| **SOLID-D** | 내부 DNS (CoreDNS + DNS API) | (별도) | `deploy/dns/slink-dns.service` |

접근: VPN/사설망에서 포털 `http://10.0.10.89:8081/portal/index.html`. (Colab 등 외부 도달은 Named Tunnel 필요 — 후속)

## 무엇이 되고, 무엇이 공인 경로를 요구하나 (핵심)

Relay는 **URL을 보관하는 장부**일 뿐 실제 트래픽은 안 지난다. 그래서 SOLID 사설망 VM에 올려도:

| 동작 | relay 공인 경로 | 비고 |
|---|---|---|
| 아웃바운드 소비 (SOLID→외부 서비스) | ❌ 불필요 | SOLID VM 아웃바운드 인터넷으로 직접 접속 |
| 아웃바운드 등록 (학생 포털/CLI→relay) | ❌ 불필요 | SOLID 내부/VPN |
| 인바운드 INTERNAL (VPN 사용자→서비스) | ❌ 불필요 | 내부 DNS(사설 IP) + 사설망 |
| **Colab 자동 등록 (Colab→relay)** | ✅ 필요 | 인터넷에서 relay에 push → Named Tunnel/Railway |
| 인바운드 외부공개 (VPN 밖→서비스) | relay 아님 | Cloudflare 터널 + Access (Phase 2) |

→ **아웃바운드·인바운드(INTERNAL) 트랙은 이 구성(사설 VM)으로 전부 동작**한다.
Colab GPU 흐름과 인바운드 외부공개만 공인 경로(Cloudflare Named Tunnel 또는 학교 리버스 프록시)가 필요하다.

## 배포 (VPN/사설망 내부 전용)

```bash
# VM에서 1회 준비
sudo mkdir -p /opt/slink /var/lib/slink
sudo cp deploy/relay/slink-relay.service /etc/systemd/system/slink-relay.service
# slink-relay.service 안의 경로/환경변수를 본인 환경에 맞게 수정

# 코드 빌드·배포·기동(이후 코드 변경 시 재실행)
bash deploy/relay/redeploy.sh
sudo systemctl enable slink-relay     # 재부팅 생존

# 상태/로그
systemctl is-active slink-relay
journalctl -u slink-relay -f
```

영속 파일(`/var/lib/slink/*.json`)에 서비스·아웃바운드 연결·VM Agent 등록(`at-` 토큰)이 저장되어
재시작·재부팅에도 보존된다(`application.properties`의 `service.store.file` 등 참고).

## ⚠ 주의 — INTERNAL 서비스의 DNS는 SOLID-D에 있음 (교차-VM 갭)

SOLID-R(relay)은 DNS_* 미설정 → `MockDnsProvider`(로그만). 그래서 **INTERNAL scope 서비스 생성 시
SOLID-R이 부르는 `dns.createRecord`는 실제 CoreDNS(SOLID-D) zone에 안 써진다** — 그 호스트네임은 해석 안 됨.
내부 DNS는 **DNS 탭(`/api/dns/records`, SOLID-D)** 으로 직접 만드는 게 현재 동작하는 경로다.
서비스 INTERNAL→실제 DNS 자동연동을 원하면 SOLID-R이 SOLID-D의 DNS API를 호출하도록 잇는 작업이 필요(후속).
(아웃바운드·인바운드 외부공개·서비스 등록 자체는 SOLID-R 단독으로 정상 동작.)

## 후속 (이번 골격 범위 밖)

- **Cloudflare Named Tunnel**로 relay를 외부 공개(Colab 도달) — 계정·고정 도메인 확보 후.
  `docs/relay-on-vm.md` §2~3. cloudflared를 별도 systemd 유닛으로 상시 실행.
- 외부공개 인바운드 **AccessPolicy 실제 시행**(Cloudflare Access/이메일 허용) — Phase 2.
