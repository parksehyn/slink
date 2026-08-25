# Solid-Link (slink) — 프로젝트 가이드

SOLID Cloud VM ↔ Google Colab GPU 연동 + SOLID VM 서비스 공유 플랫폼.

## 현재 방향

Colab GPU 연결(기존 핵심 기능)을 유지하면서, SOLID VM에서 실행한 서비스를
학생이 포털에 등록·공유하는 **Service Portal**로 확장 중이다.

- 상세 설계 → [`docs/service-portal-design.md`](docs/service-portal-design.md)
- 단계별 구현 계획 → [`docs/roadmap.md`](docs/roadmap.md)
- 구현 완료 기능 → [`docs/progress.md`](docs/progress.md)

## 아키텍처

```
[기존] Colab → Cloudflare Tunnel → Relay → SOLID VM (slink connect)

[확장] Service Portal (DNS 탭 / 터널링 탭)
  ├─ DNS 탭   : 내부 DNS 레코드(A/CNAME) 콘솔   (DnsProvider, 현재 모의 구현)
  └─ 터널링 탭:
       ├─ 아웃바운드: 외부가 연 터널 등록 → SOLID 접근 (Colab 일반화, /api/connections)
       └─ 인바운드  : SOLID VM 서비스 외부 공개 (Cloudflare Tunnel, VM Agent 실제 동작)
                      접근 정책: 단국대 내부 전용(기본) / 허용 대상 지정 — 시행 예정
```

## 컴포넌트

| 컴포넌트 | 위치 | 역할 |
|---------|------|------|
| Relay / Registry | Railway (Spring Boot 4) | Session API + Service Registry API |
| slink-cli | PyPI (`slink-cli`) | Colab GPU 연결 CLI |
| colab_agent | Colab / Railway `/agent` | JupyterLab + Cloudflare Tunnel |
| Service Portal | `/portal/index.html` | 서비스 등록·조회 웹 UI |

## 파일 구조

```
src/main/java/com/solid/connectgpu/
├── controller/   SessionController, UserController, ServiceController, VmAgentController,
│                 ExternalResourceController, ExternalResourceAgentController,
│                 DnsRecordController, OutboundConnectionController, VmController
├── service/      SessionService, UserService, ServiceRegistry,
│                 AgentRegistry(통합: 구 VmAgentRegistry+ExternalResourceRegistry),
│                 RegistrationTokenRegistry, DnsRecordRegistry, OutboundConnectionRegistry
├── model/        Session, User, ServiceEntry, ServiceScope, ServiceStatus, Protocol,
│                 Agent, AgentLocation, AgentService, ResourceType, ResourceStatus,
│                 AccessPolicy, DnsRecord, DnsRecordType, OutboundConnection, ConnectionType, VmInfo
├── dns/          DnsCodec, DnsUdpServer (자체 구현 DNS 응답기 — RFC 1035 직접 구현,
│                 dns.server.enabled로 활성, DnsRecordRegistry를 zone 데이터로 직접 서빙)
├── port/         DnsProvider, TunnelProvider, CloudStackProvider (인터페이스)
│   └── impl/     MockDnsProvider, MockTunnelProvider, MockCloudStackProvider
└── dto/          (각 API 요청·응답 record)
agents/slink/     slink-cli 소스 (PyPI 배포)
src/main/resources/static/portal/   Service Portal 웹 UI (index=2탭, detail)
docs/             설계·로드맵·진행 문서
```

## 주요 설정

- Gradle 8.14 (9.x는 IBM_SEMERU 오류 발생)
- 포트: `${PORT:8081}` (Railway 환경변수 동적 할당)
- 세션 TTL: 12시간 / 5분마다 만료 정리

## 구현 원칙

- 실제 DNS·터널 연동은 `DnsProvider`·`TunnelProvider` 인터페이스로 분리, 현재 모의 구현 사용
- 기존 Session/User API 회귀 없이 Service API 추가
- 계획된 기능과 구현 완료된 기능을 UI·문서에서 명확히 구분

## API 요약

| 경로 | 용도 |
|------|------|
| `POST/GET/DELETE /api/session/*` | Colab 세션 (기존) |
| `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout` | SOLID(CloudStack) 세션 인증 — DNS·VM·서비스·터널링 (토큰 `slk-…`) |
| `POST /api/users/register`, `GET /api/users/me` | slink 자체 키 발급 — Colab 세션(`/api/session`) 전용 (`sk-dku-…`, 단계적 폐지 예정) |
| `POST/GET/PATCH/DELETE /api/services/*` | Service Registry (인바운드, vmId 기반·소유권 검증). SOLID 인증 |
| `POST/DELETE /api/services/{id}/publish` | 외부 공개 제어. SOLID 인증 |
| `GET/POST /api/dns/records`, `GET/PATCH/DELETE /api/dns/records/{id}` | 내부 DNS 레코드(A=vmId 기반·소유권/사설IP 검증, CNAME). SOLID 인증·표준 에러 |
| `GET/POST /api/connections`, `DELETE /api/connections/{id}` | 아웃바운드 외부 연결. SOLID 인증 |
| `GET /api/vms` | 내 SOLID VM 목록 (CloudStack, SOLID 인증, 모의 가능) |
| `POST /api/agents/register`(SOLID 인증) → `at-` 발급, `/{id}/heartbeat`·`/{id}/report`(에이전트 토큰 `at-`) | VM Agent |
| `POST /api/resources/registration-token`(SOLID 인증) → `rt-`, `/api/resources/agents/register`(`rt-`) → `rat-`, `/{id}/heartbeat`·`/{id}/report`(`rat-`), `GET/DELETE /api/resources[/{id}]` | 외부 자원 Agent (통합 AgentRegistry, location=COLAB/EXTERNAL) |
| `GET /api/metrics` | Relay 실시간 지표 (Agent 위치별 집계·서비스·JVM). SOLID 인증 |

## 관련 문서

- [`docs/service-portal-design.md`](docs/service-portal-design.md) — Service Portal 상세 설계 (기준 문서)
- [`docs/tabs-redesign.md`](docs/tabs-redesign.md) — 도메인/터널링 탭 재구성 확정 설계 + 구현 플랜
- [`docs/dns-api-spec.md`](docs/dns-api-spec.md) — DNS 서비스 API 명세 + 구현 현황 (SOLID 인증·vmId 기반·DNS Server VM 자족형)
- [`docs/dns-naming-policy.md`](docs/dns-naming-policy.md) — 내부 DNS 이름 정책 (전역 유일=현재 / 학번 네임스페이스=검토, 미팅 논의용)
- [`docs/todo-next.md`](docs/todo-next.md) — 다음 작업 TODO + 미팅 안건 + 재개 치트시트 (DNS 이후)
- [`docs/roadmap.md`](docs/roadmap.md) — 단계별 구현 계획
- [`docs/progress.md`](docs/progress.md) — 구현 완료 기능 기록
- [`docs/demo-theory.md`](docs/demo-theory.md) — 외부 공개(역방향 터널링) 데모 동작 이론
- [`docs/demo-runbook.md`](docs/demo-runbook.md) — 데모 실행 순서(재배포 후 재셋업 포함) + 트러블슈팅
- [`docs/internal-dns-requirements.md`](docs/internal-dns-requirements.md) — 내부 DNS 실연동에 필요한 권한·정보·정책
- [`docs/external-resource-connection.md`](docs/external-resource-connection.md) — 아웃바운드를 외부 자원 연결로 재정의한 방향, Colab 통합 현황·후속 계획
- [`docs/unified-agent-design.md`](docs/unified-agent-design.md) — 통합 Agent 설계 (인바운드/아웃바운드 → 단일 Agent+AgentList 모델, 미팅 피드백 반영)
- [`docs/relay-on-vm.md`](docs/relay-on-vm.md) — Relay를 SOLID VM에 올리는 배포 설계 (Named Tunnel, 별도 트랙)
- [`docs/archive/design-a.md`](docs/archive/design-a.md) — (아카이브) Colab 연결 A안 설계 기록 — 구현 완료, 역사 참고용
