# Relay 서버를 SOLID VM에 올리기 (별도 배포 트랙)

> 작성일: 2026-06-21
> 상태: 설계 (코드·인프라 변경 없음 — 실행은 다음 트랙)
> 관련 문서: [progress.md](progress.md), [demo-theory.md](demo-theory.md), [service-portal-design.md](service-portal-design.md)

현재 Relay(Spring Boot)는 Railway(공인)에 떠 있다. 이를 **SOLID VM 안으로 옮기고 싶다**는 요구에 대한 설계.
포털 2탭 작업과는 성격이 다른 배포/인프라 트랙이므로 분리해 정리한다.

## 1. 왜 단순 이전이 안 되는가 (핵심 제약)

SOLID VM은 사설 IP(`10.0.X.X`) + OpenVPN 전용 → **외부에서 인바운드로 못 들어온다.**
그런데 Relay에는 다음이 닿아야 한다.

| 닿아야 하는 주체 | 위치 | 사설망 VM Relay에 도달? |
|------------------|------|--------------------------|
| VM Agent | SOLID VM 내부 | ✅ (사설망/Shared Network) |
| slink CLI | SOLID VM 내부 | ✅ |
| 학생 브라우저(포털) | VPN 접속 시 | ✅ / VPN 없으면 ❌ |
| **Colab / 외부 서비스** | 인터넷(Google) | ❌ **불가** |

→ 외부(Colab) 도달이 필요한 한, Relay는 **공인 도달 경로**가 있어야 한다.
이것이 progress.md에 "불가능 — Railway 유지"로 기록됐던 이유다.

## 2. 해법 — Named Tunnel로 VM의 Relay를 공개

PUBLIC 서비스 공개에 쓰는 것과 **같은 역방향 터널** 메커니즘으로 Relay 자신을 외부에 노출한다(dogfooding).

```text
Colab/외부 ─→ relay.<도메인> ─→ Cloudflare Edge ─→ cloudflared(VM) ─→ localhost:8081 (Relay)
```

단, **Quick Tunnel은 부적합**하다: 재시작마다 `trycloudflare.com` URL이 바뀌는데
포털·Agent·CLI·Colab이 모두 "고정된 한 주소"를 가리켜야 하기 때문.
→ **Named Tunnel**(고정 도메인) 또는 학교 공인 리버스 프록시가 필요하다.

## 3. 필요한 것

- Cloudflare 계정 + 관리 가능한 도메인 1개 (예: `relay.dku-slink.example`)
- Named Tunnel 토큰 (`cloudflared tunnel`), VM에서 상시 실행 (systemd 서비스 권장)
- Relay는 이미 `${PORT:8081}`로 포트 주입을 지원 → VM에서 그대로 기동 가능
- (선택) 학교가 공인 도메인/리버스 프록시를 제공하면 Cloudflare 없이도 가능

## 4. 이전 시 바꿔야 할 base URL 지점

"Relay 주소"를 가리키는 곳을 새 고정 도메인으로 교체한다.

- `colab_agent` / `slink-agent`의 Relay base URL (현재 `https://slink-production-3e7d.up.railway.app`)
- `slink-cli`의 Relay base URL (`~/.slinkrc` 또는 기본값)
- 포털은 자기를 내려준 서버로 상대경로(`/api/...`) 요청 → Relay와 같은 호스트에서 서빙되면 자동 일치
- `/agent` 부트스트랩이 주입하는 `{{RELAY_URL}}`(X-Forwarded-Host 기반) — 터널이 헤더를 보존하는지 확인

## 5. 트레이드오프

| 방식 | 장점 | 단점 |
|------|------|------|
| Railway 유지(현행) | 설정 0, 항상 공인 | 외부 의존, 비용/쿼터 |
| VM + Named Tunnel | 학교 자원 내 운영, dogfooding | Cloudflare 계정·도메인·터널 상시 운영 필요 |
| 내부 전용(터널 없음) | 가장 단순 | Colab 등 외부가 Relay에 못 닿음 → 아웃바운드 외부 도달 깨짐 |

## 6. 다음 단계 (이 트랙 진행 시)

1. 고정 도메인 + Cloudflare Named Tunnel(또는 학교 공인 경로) 확보
2. VM에 Relay + cloudflared(systemd) 배포, 헬스체크
3. Agent/CLI/Colab base URL 교체 후 왕복 테스트
4. 영속 저장소 도입 검토 (재시작 시 서비스·DNS 레코드 유지)

## 7. 이번 테스트 구성 — 사설 VM에 plain jar (Named Tunnel 없이)

DNS 전체 흐름 검증용. **VPN/사설망 안에서만** 접근하면 되므로 Named Tunnel 없이 VM에 jar로 바로 띄운다.
(이 구성에선 외부 공개·Colab은 동작 안 함 — 그건 위 Named Tunnel 트랙. 브라우저는 노트북이 **SOLID VPN 접속** 상태여야 사설 Relay에 닿음.)

### Relay (VM-R)
```bash
./gradlew bootJar                         # build/libs/*.jar
scp build/libs/*.jar  vm-r:/opt/slink/relay.jar
# VM-R 에서:
PORT=8081 java -jar /opt/slink/relay.jar  # 또는 systemd 유닛
sudo ufw allow 8081                       # VPN/사설에서 접근 허용
```
사용자 등록(API Key 발급) — VM-R에서:
```bash
curl -X POST http://localhost:8081/api/users/register \
  -H 'Content-Type: application/json' \
  -d '{"studentId":"32211690","email":"you@dankook.ac.kr"}'
```

### 노트북에서 포털 서빙 + Relay 주소 맞추기
정적 디렉터리를 웹루트로 서빙(경로가 `/portal/...`로 유지되도록):
```bash
cd src/main/resources/static && python3 -m http.server 5500
# 브라우저: http://localhost:5500/portal/index.html  (SOLID VPN 켠 상태)
```
- 로그인 화면의 **Relay 주소**에 `http://<VM-R 사설IP>:8081` 입력(또는 `?api=` 쿼리). localStorage에 저장됨.
- Relay에 CORS가 켜져 있어(`config/WebConfig.java`) 다른 origin에서 호출 가능. origin 제한은 `slink.cors.allowed-origins`.

### 도달성 요약 (노트북 VPN 접속 가정)
| 연결 | 됨? |
|------|-----|
| 브라우저(노트북) → Relay(VM-R:8081) | ✅ VPN |
| dns-agent(VM-D) → Relay(VM-R) | ✅ 사설망 |
| 테스트 VM → DNS(VM-D:53) | ✅ 사설망 |
| Colab → Relay(VM-R) | ❌ (공개 필요 → Named Tunnel) |
