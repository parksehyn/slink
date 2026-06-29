# 다음 작업 (TODO) — DNS 서비스 이후

> 최종 업데이트: 2026-06-22
> 관련: [dns-api-spec.md](dns-api-spec.md), [dns-naming-policy.md](dns-naming-policy.md),
> [internal-dns-requirements.md](internal-dns-requirements.md), [progress.md](progress.md)

## ✅ 현재 상태 (동작 확인됨)

- **solid-D**: 앱(SOLID 인증 + 실제 CloudStack + `ZoneFileDnsProvider`) + CoreDNS, **둘 다 systemd enabled**(재부팅 생존).
- **전 사이클 동작**: SOLID 로그인 → 내 VM 조회 → A 레코드(vmId) 생성 → `dig` 해석 → 삭제 → 해석 불가.
- **멀티유저 안전**: DNS 이름 전역 유일(하이재킹 방지). 레코드 파일 영속.
- **화면 빠른 수정**: 외부 정적 서빙 → 프론트만 바꿀 땐 `git pull` + 새로고침(재빌드 불필요).
- 코드: 브랜치 `feat/dns-solid-auth` (main 미병합 → Railway 영향 없음).

## 🔧 우리가 할 수 있는 것 (코드/배포)

- [ ] **PR 병합 결정**: `feat/dns-solid-auth` → main. (병합 시 Railway 자동 재배포 → 공개 포털이 SOLID 로그인으로 바뀌고 터널링 탭 영향 — 인지하고 결정)
- [~] **HTTPS/리버스 프록시**(nginx/caddy) — 현재 평문 http. **설계 문서화 완료**(dns-api-spec.md "HTTPS — 계획"), 배포 파일은 운영팀 내부 인증서 정책 확정 후.
- [x] 전역 유일 정책 보완: **예약어 차단**(ns, www) + **학생당 레코드 개수 상한**(기본 20) + **방치 레코드 회수**(expire-days) 구현·테스트 완료(`DnsRecordPolicyTest`). TTL 상한은 기존 `clampTtl`(30~86400)로 이미 동작. 수치는 `application.properties`로 조정.
- [ ] (미팅 결정 시) **학번 네임스페이스** 또는 익명 토큰 적용 — `dns-naming-policy.md`
- [ ] **터널링 트랙 시작**(DNS 끝났으니) — 이때 `sk-dku-` 전면 제거 나머지(Colab CLI·VM Agent·SessionController)도 함께

## 🏛 운영팀 의존 — 미팅 안건 ★ 실배포 핵심 관문

- [ ] **OpenVPN이 우리 DNS(10.0.11.23)를 push** (`push "dhcp-option DNS 10.0.11.23"` + `.solid.internal` 도메인)
- [ ] **VM들이 이 DNS를 resolver로** 보게 (resolv.conf / DHCP / VM 이미지 기본값)
- [ ] `.solid.internal` 존 운영 합의 (우리가 권한지로 운영)
- [ ] **VM 간 / VPN→VM 포트 접근 허용** (이름 풀려도 방화벽 막으면 무의미)
- [ ] 이름 정책 최종 결정 (전역 유일 vs 학번 네임스페이스)

> 미팅엔 `internal-dns-requirements.md`(권한) + `dns-naming-policy.md`(이름) + `dns-api-spec.md`(구현 현황) 가져가면 됨.

## ⚡ 빠른 재개 (내일 시작용 치트시트)

```bash
# 접속(포털): VPN 연결 후
http://10.0.11.23:8081/portal/index.html        # 로그인: 학번/비번/도메인

# 재배포(코드 변경 시)
cd ~/slink && bash deploy/dns/redeploy.sh

# 화면만 바꿨을 때
cd ~/slink && git pull                           # + 브라우저 새로고침 (재빌드 X)

# 상태/로그
systemctl is-active slink-dns coredns
journalctl -u slink-dns -f
dig @10.0.11.23 <이름>.solid.internal +short

# 노트북에서 이름 해석(테스트용, 관리자 PowerShell)
Add-DnsClientNrptRule -Namespace ".solid.internal" -NameServers "10.0.11.23"
ipconfig /flushdns
```
