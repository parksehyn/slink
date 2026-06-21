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
│                 DnsRecordController, OutboundConnectionController, VmController
├── service/      SessionService, UserService, ServiceRegistry, VmAgentRegistry,
│                 DnsRecordRegistry, OutboundConnectionRegistry
├── model/        Session, User, ServiceEntry, ServiceScope, ServiceStatus, Protocol,
│                 AccessPolicy, DnsRecord, DnsRecordType, OutboundConnection, ConnectionType, VmInfo
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
| `POST /api/users/register`, `GET /api/users/me` | 사용자 인증 |
| `POST/GET/PATCH/DELETE /api/services/*` | Service Registry (인바운드) + 접근 정책 |
| `POST/DELETE /api/services/{id}/publish` | 외부 공개 제어 |
| `GET/POST /api/dns/records`, `PATCH/DELETE /api/dns/records/{id}` | 내부 DNS 레코드(A/CNAME, 모의) |
| `GET/POST /api/connections`, `DELETE /api/connections/{id}` | 아웃바운드 외부 연결 |
| `GET /api/vms` | 내 SOLID VM 목록 (CloudStack, 모의) |
| `POST /api/agents/register`, `/{id}/heartbeat`, `/{id}/report` | VM Agent |

## 관련 문서

- [`docs/service-portal-design.md`](docs/service-portal-design.md) — Service Portal 상세 설계 (기준 문서)
- [`docs/roadmap.md`](docs/roadmap.md) — 단계별 구현 계획
- [`docs/progress.md`](docs/progress.md) — 구현 완료 기능 기록
- [`docs/demo-theory.md`](docs/demo-theory.md) — 외부 공개(역방향 터널링) 데모 동작 이론
- [`docs/demo-runbook.md`](docs/demo-runbook.md) — 데모 실행 순서(재배포 후 재셋업 포함) + 트러블슈팅
- [`docs/internal-dns-requirements.md`](docs/internal-dns-requirements.md) — 내부 DNS 실연동에 필요한 권한·정보·정책
- [`docs/relay-on-vm.md`](docs/relay-on-vm.md) — Relay를 SOLID VM에 올리는 배포 설계 (Named Tunnel, 별도 트랙)
- [`docs/design-a.md`](docs/design-a.md) — Colab 연결 설계 기록
