# Solid-Link (slink) — AI 에이전트 가이드

## 프로젝트 현황

SOLID Cloud VM ↔ Google Colab GPU 연동 시스템.
**Colab GPU 연결(완료)**, **Service Portal(1~3단계 MVP 완료)**, **내부 DNS(미구현)** 가 공존한다.

## 구현 완료 기능

### Colab 연결
- Colab JupyterLab + Cloudflare Quick Tunnel 생성
- Railway Relay 서버 (Spring Boot 4): Session CRUD, User 등록·인증
- `slink-cli` (PyPI): `slink init`, `slink connect`, `slink disconnect`, `slink status`

### Service Portal (1~2단계)
- Service Registry API: 서비스 등록·조회·수정·삭제·외부공개 (`/api/services/*`)
- Service Portal 웹 UI: `add.html` / `index.html` / `detail.html`
- `DnsProvider`·`TunnelProvider` 인터페이스 (현재 MockDnsProvider만 사용, TunnelProvider는 Agent로 대체됨)

### VM Agent + Cloudflare Quick Tunnel (3단계 MVP) — 2026-06-07
- `/api/agents/register` — VM Agent 등록, agentId + agentToken 발급
- `/api/agents/{id}/heartbeat` — Agent heartbeat + 대기 명령 반환
- `/api/agents/{id}/report` — Agent가 TUNNEL_READY / TUNNEL_STOPPED / TUNNEL_FAILED 보고
- `publish()`: MockTunnelProvider 직접 호출 제거 → `PENDING` 상태 + `OPEN_TUNNEL` 명령으로 전환
- `unpublish()`: PENDING 취소 or `CLOSE_TUNNEL` 명령
- TTL 만료 시 Agent가 살아있으면 `CLOSE_TUNNEL` 명령, 없으면 즉시 정리
- `slink agent start --instance-id solid-XXXX` — Python VM Agent CLI (cloudflared 관리)
- `ServiceStatus.PENDING` 추가
- `ServiceResponse.pendingCommand` 필드 추가

### 보안 수정 (2026-06-07 이전)
- 중복 등록 차단 (409 Conflict)
- `unpublish`·TTL 만료 시 scope를 publish 이전 값으로 복원
- `create`/`update`에서 `scope=PUBLIC` 직접 지정 시 400
- `TunnelProvider.openTunnel`에서 `privateIp` 제거 → 실제 구현 시 VM Agent loopback으로만 연결
- 이름 변경 연동: 서비스 이름 변경 시 `internalHostname`·DNS 레코드 동시 갱신
- publish된 서비스의 scope를 PATCH로 변경하면 터널 자동 종료 명령
- 포털 UI: PUBLIC 직접 선택 제거, 외부 공개는 publish 버튼 전용

## 미구현 (모의 구현 상태)

- 실제 내부 DNS 연동 (SOLID 운영 환경 DNS 설정 권한 필요)
- CloudStack API 연동 (VM 인스턴스 소유권 검증)
- 팀 네트워크 권한 강제

## 아키텍처 핵심 제약

- SOLID VM은 사설 IP (`10.0.X.X`). **Relay는 반드시 공인 IP 외부 서버여야 함** (현재 Railway).
- TunnelProvider 인터페이스는 유지하지만, ServiceRegistry에서는 더 이상 직접 호출하지 않음.
  실제 터널은 VM Agent가 자신의 loopback(localhost:{port})으로 cloudflared를 실행.
- **실제 TunnelProvider 활성화는 CloudStack 소유권 검증 완료 후에만 한다.**
- 기존 `/api/session/*`, `/api/users/*` API를 깨뜨리지 않는다.

## 알려진 보안 제약 (운영 전 해결 필요)

- **계정 자기 주장**: 이메일·학번을 스스로 입력하므로 누구나 타인 이메일 선점 가능.
  운영 전 SOLID SSO/LDAP/CloudStack 계정 검증 연동 필수. (`UserService.java` 상단 TODO 참조)
- **VM 소유권 미검증**: `instanceId`·`privateIp`는 사용자 입력값이며 CloudStack으로 검증하지 않음.
  Agent 등록 시에도 instanceId를 자기 신고함. `VmAgentController.java` 상단 TODO 참조.
- **Agent 인증 범위**: agentToken은 메모리 내 검증만 함 (Relay 재시작 시 소멸).
  운영 전 영속 저장소 + 인증 강화 필요.

## 코드 위치

```
src/main/java/com/solid/connectgpu/
├── controller/   SessionController, UserController, ServiceController,
│                 AgentController (Colab agent.py 반환),
│                 VmAgentController (VM Agent API)
├── service/      SessionService, UserService, ServiceRegistry, VmAgentRegistry
├── model/        Session, User, ServiceEntry, ServiceScope, ServiceStatus,
│                 Protocol, AgentCommand, VmAgent
├── port/         DnsProvider, TunnelProvider (인터페이스)
│   └── impl/     MockDnsProvider, MockTunnelProvider (사용되지 않음)
└── dto/          AgentRegisterRequest/Response, AgentHeartbeatResponse,
                  AgentReportRequest, (기존 DTO들)
src/main/resources/static/portal/   add.html, index.html, detail.html
agents/slink/slink.py               slink-cli + slink agent start
src/test/java/com/solid/connectgpu/ SessionApiTest, UserApiTest,
                                    ServiceApiTest, AgentApiTest
```

## 테스트

```
.\gradlew.bat test --rerun-tasks
```

- `SessionApiTest`: 세션 CRUD 회귀 (6개)
- `UserApiTest`: 등록·인증·중복 등록 차단 (7개)
- `ServiceApiTest`: Service Registry CRUD + 상태 전환 + 보안 시나리오 (20개)
- `AgentApiTest`: Agent 등록·heartbeat·TUNNEL_READY/STOPPED/FAILED·TTL 만료 (9개)
- 모두 `@SpringBootTest` + `MockMvc` 사용.
- 현재: **43개 통과, 실패 0**

## VM Agent 실행 (SOLID VM에서)

```bash
# cloudflared 설치 (없는 경우)
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 \
  -o cloudflared && chmod +x cloudflared && sudo mv cloudflared /usr/local/bin/

# slink-cli 설치 (없는 경우)
pipx install slink-cli

# Agent 시작 (포탈에서 "외부 공개 시작" 클릭 후 실행)
slink agent start --instance-id solid-32211690
```

Agent가 실행되면 10초마다 Relay를 polling하여 `OPEN_TUNNEL` 명령을 확인하고,
cloudflared Quick Tunnel을 열어 `trycloudflare.com` URL을 Relay에 보고한다.

## 참조 문서

- [`docs/service-portal-design.md`](docs/service-portal-design.md) — Service Portal 상세 설계 (기준 문서)
- [`docs/roadmap.md`](docs/roadmap.md) — 단계별 구현 계획 + 보안 제약 ⚠️
- [`docs/progress.md`](docs/progress.md) — 구현 완료 기능 기록
- [`docs/design-a.md`](docs/design-a.md) — Colab 연결 설계 기록
