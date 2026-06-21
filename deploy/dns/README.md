# 내부 DNS 실테스트 — CoreDNS on SOLID VM

> 목표: `solid.internal` 존을 **실제로 해석**시키는 DNS 서버를 VM 하나에 띄우고,
> 다른 VM들에서 `web.solid.internal → 10.0.10.11` 처럼 이름으로 접근되는지 검증한다.
> 운영팀 권한 없이 **본인이 가진 VM들끼리** 바로 가능하다.
> 관련: [`../../docs/internal-dns-requirements.md`](../../docs/internal-dns-requirements.md), [`../../docs/relay-on-vm.md`](../../docs/relay-on-vm.md)

## 구성도

```
[VM-A: DNS 서버]  CoreDNS, 10.0.10.10:53
      ▲ 53/udp,tcp
      │  "web.solid.internal?" → 10.0.10.11
[VM-B] [VM-C] ...  resolv.conf 또는 dig @10.0.10.10 으로 질의
```

## 0. 전제

- VM 2~3개 (CloudStack에서 생성). 하나는 DNS 서버(VM-A), 나머지는 클라이언트.
- VM들이 **같은 사설망(Shared Network)** 에 있어 서로 IP로 통신 가능.
- 각 VM의 사설 IP 확인: `ip -4 addr` (예: VM-A=10.0.10.10, VM-B=10.0.10.11 …).
- `db.solid.internal`의 IP를 **실제 VM IP로 교체**한다.

## 1. DNS 서버 VM(VM-A)에 CoreDNS 기동

### 방법 A — 단일 바이너리 (Docker 없이, 권장)

```bash
# 1) CoreDNS 바이너리 설치 (Linux amd64 예시 — 최신 버전은 github.com/coredns/coredns/releases 참고)
curl -fsSL -o coredns.tgz https://github.com/coredns/coredns/releases/download/v1.11.1/coredns_1.11.1_linux_amd64.tgz
tar xzf coredns.tgz && sudo mv coredns /usr/local/bin/

# 2) 설정 배치 (이 폴더의 Corefile, db.solid.internal 를 VM-A로 복사 후)
sudo mkdir -p /etc/coredns
sudo cp Corefile db.solid.internal /etc/coredns/

# 3) 포그라운드 실행 (테스트). 53은 권한 필요 → sudo
sudo coredns -conf /etc/coredns/Corefile
```

상시 실행은 systemd 유닛으로 (`/etc/systemd/system/coredns.service`):

```ini
[Unit]
Description=CoreDNS
After=network.target
[Service]
ExecStart=/usr/local/bin/coredns -conf /etc/coredns/Corefile
Restart=always
[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl daemon-reload && sudo systemctl enable --now coredns
```

### 방법 B — Docker

```bash
# 이 폴더(deploy/dns) 통째로 VM-A에 복사 후
docker compose up -d
docker compose logs -f
```

## 2. 방화벽 — 53 포트 열기 (가장 흔한 실패 지점)

VM-A에서 사설망 쪽 53/udp·tcp 인바운드 허용:
```bash
sudo ufw allow 53/udp && sudo ufw allow 53/tcp   # ufw 사용 시
```
CloudStack 네트워크 ACL/시큐리티그룹에서도 사설 대역의 53 인바운드가 막혀 있지 않은지 확인.

## 3. 다른 VM에서 즉시 테스트 (클라이언트 설정 불필요!)

`dig @서버IP` 로 직접 질의하면 resolv.conf를 안 건드려도 검증됩니다:

```bash
dig @10.0.10.10 web.solid.internal +short      # → 10.0.10.11
dig @10.0.10.10 api.solid.internal +short      # → 10.0.10.12
dig @10.0.10.10 app.solid.internal +short      # → web.solid.internal. 그리고 10.0.10.11 (CNAME 체인)
# nslookup 파:  nslookup web.solid.internal 10.0.10.10
```
여기서 IP가 나오면 **DNS 서버 + 네트워크 경로는 OK**. 안 나오면 → 2번(방화벽) 또는 VM 간 통신 확인.

## 4. 클라이언트 VM이 *항상* 이 DNS를 쓰게 (투명 해석)

`dig @...` 없이 그냥 `curl http://web.solid.internal:3000` 되게 하려면 resolver를 지정한다.

### systemd-resolved (Ubuntu 기본) — 스플릿 DNS 권장
`/etc/systemd/resolved.conf`:
```ini
[Resolve]
DNS=10.0.10.10
Domains=~solid.internal     # solid.internal 질의만 이 서버로, 나머지는 기존 DNS 유지
```
```bash
sudo systemctl restart systemd-resolved
resolvectl status            # solid.internal 라우팅 확인
```

### resolv.conf 직접 (systemd-resolved 없을 때)
```bash
echo "nameserver 10.0.10.10" | sudo tee /etc/resolv.conf
```
(이 경우 인터넷 도메인은 Corefile의 `forward . 8.8.8.8` 가 처리)

확인:
```bash
ping web.solid.internal
curl http://web.solid.internal:3000
```

## 5. 레코드 추가/수정/삭제

`db.solid.internal` 편집 → **SOA serial 증가**(필수) → 저장. CoreDNS `file` 플러그인이 serial 변경을 감지해 자동 reload.
```
web2    IN  A   10.0.10.13     ; 새 VM 추가 예시
```
serial을 안 올리면 반영 안 됩니다(가장 흔한 실수).

## 6. 포털 → 실제 DNS 반영 (`dns-agent.py`)

`dns-agent.py`가 Relay의 `/api/dns/records`를 폴링해 위 존 파일을 자동으로 다시 씁니다.
→ **포털 DNS 탭에서 추가/수정/삭제한 레코드가 CoreDNS에 반영**됩니다. (백엔드 변경 없음 — 기존 인증 API 재사용)

```
포털 "추가" → /api/dns/records → Relay 레지스트리
                                    ▲ 폴링 (Bearer API Key)
                      [dns-agent.py on VM-A] → db.solid.internal 재작성 + serial++ → CoreDNS reload
[다른 VM] dig web.solid.internal → 실제 응답 ✓
```

### 실행 (VM-A, CoreDNS와 같은 VM)
```bash
sudo mkdir -p /opt/slink && sudo cp dns-agent.py /opt/slink/
export SLINK_RELAY_URL=http://<VM-R-사설IP>:8081   # Relay VM 주소
export SLINK_API_KEY=sk-dku-...                    # 포털 로그인과 같은 키
export SLINK_NS_IP=10.0.10.10                      # 이 DNS VM 자신
python3 /opt/slink/dns-agent.py
```
상시 실행은 `dns-agent.service`(systemd) — 값 수정 후:
```bash
sudo cp dns-agent.service /etc/systemd/system/slink-dns-agent.service
sudo systemctl daemon-reload && sudo systemctl enable --now slink-dns-agent
journalctl -u slink-dns-agent -f
```

> ⚠ 에이전트가 켜지면 존 파일을 **자동 관리**합니다(직접 편집 금지 — 덮어써짐). 수동 테스트(3·5번)는 에이전트를 끄고 하세요.
> Relay가 사설 VM이면 같은 사설망의 VM-A가 바로 폴링 가능(공인 도달 불필요).

### 더 상용처럼(push) 가려면
CoreDNS+폴링 대신 **PowerDNS(REST API)** 로 바꾸고 `RealDnsProvider`가 API로 PUT. Relay가 DNS 서버에 직접 닿아야 함(같은 사설망이면 OK). 코드는 `DnsProvider` 인터페이스만 교체.

## 트러블슈팅

| 증상 | 원인 | 해결 |
|------|------|------|
| `dig @서버` 타임아웃 | 53 포트 차단/VM 간 불통 | 2번 방화벽, `ping 서버IP` |
| 레코드 바꿨는데 그대로 | serial 안 올림 | SOA serial +1 |
| `SERVFAIL` | 존 파일 문법 오류 | `coredns` 로그 확인, `$ORIGIN`/끝점(.) 확인 |
| 인터넷 도메인 안 됨 | resolv.conf를 서버만 가리킴 + forward 미동작 | Corefile `forward .` 블록 확인 또는 스플릿 DNS(4번) |
| `53 권한 거부` | 비루트로 53 바인드 | `sudo` 또는 `setcap cap_net_bind_service` |
