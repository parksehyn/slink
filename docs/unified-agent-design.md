# 통합 Agent 설계 — 단일 솔루션 + 자체 터널링

> 작성일: 2026-07-03
> 상태: 설계 초안 (미팅 피드백 반영, 구현 전)
> 관련 문서: [external-resource-connection.md](external-resource-connection.md), [tabs-redesign.md](tabs-redesign.md), [dns-naming-policy.md](dns-naming-policy.md), [relay-on-vm.md](relay-on-vm.md)

## 1. 배경 — 왜 다시 설계하는가

지금까지 기능을 이렇게 나눠서 구현해 왔다.

```text
인바운드   : SOLID VM 서비스를 외부에 공개 (VM Agent + Cloudflare Tunnel)
아웃바운드 : 외부(Colab, 집 PC 등) 자원을 SOLID에서 사용 (외부가 연 quick tunnel 등록)
```

미팅 피드백은 두 가지이고, 서로 맞물린 하나의 그림이다.

> **(1) "Relay와 Agent 관점에서 보면 둘은 같은 것이다."**
> **(2) "Cloudflare 같은 외부 터널 서비스를 쓰지 말고 직접 구현하라."**

(1) 인바운드/아웃바운드는 *사용자 관점*의 구분일 뿐이다. Relay와 Agent 관점에서는
두 경우 모두: NAT 뒤에서 서비스가 돌고, 그 옆의 Agent가 **밖으로** Relay에 접속해
등록·생존 신호를 보내고, Relay는 **AgentList** 하나를 관리하며, 사용자가 항목을
**open** 하면 연결이 열린다. Agent가 SOLID VM 위에 있는지 Colab·집 PC 위에 있는지는
**Agent의 속성(위치)이지 별개의 기능이 아니다.**

(2) 지금까지 인바운드/아웃바운드의 메커니즘이 달라 *보였던* 이유의 절반은 Cloudflare다.
인바운드는 cloudflared가 터널을 열고, 아웃바운드는 외부에서 연 quick tunnel URL을
등록하는 식으로 달랐다. Cloudflare를 제거하고 **Relay가 직접 트래픽을 중계**하면
두 경우는 기술적으로도 완전히 동일해진다 — 단일 솔루션이 자연스럽게 완성된다.

## 2. 현재 상태 진단

### 2.1 같은 일을 하는 시스템이 4벌

| 시스템 | API | Agent 토큰 | 저장소 | 정체 |
|---|---|---|---|---|
| Colab 세션 | `/api/session/*` | `sk-dku-` (장기 키) | SessionService | Agent가 터널 URL 등록 |
| 아웃바운드 연결 | `/api/connections` | — (수동 등록) | OutboundConnectionRegistry | 외부 URL 수동 CRUD |
| VM Agent (인바운드) | `/api/agents/*` | `dt-`→`at-` | VmAgentRegistry | register / heartbeat / report |
| 외부 자원 (feat/external-resource) | `/api/resources/*` | `rt-`→`rat-` | ExternalResourceRegistry | register / heartbeat / report |

`VmAgentController`와 `ExternalResourceAgentController`는 엔드포인트 구조
(register / heartbeat / report)까지 동일하다. 새 자원 유형마다 controller + registry +
토큰 체계를 복제하는 패턴 — 이것이 "덕지덕지"의 실체다.

통합의 씨앗은 이미 있다: `RegistrationTokenRegistry`는 인바운드(`dt-`)와
아웃바운드(`rat-`) 등록 토큰을 **하나의 발급소**에서 처리한다.

### 2.2 외부 의존성 현황

| 의존성 | 사용처 | 처리 방향 |
|---|---|---|
| Cloudflare Quick Tunnel | 인바운드 공개(VM Agent), Colab Agent | **제거 — 자체 터널로 대체 (§5)** |
| Railway 호스팅 | Relay 배포 | SOLID VM으로 이전 ([relay-on-vm.md](relay-on-vm.md), Named Tunnel 전제 부분은 자체 터널 설계로 수정 필요) |
| DNS | 없음 — `MockDnsProvider` (모의 구현) | **DNS 서버 직접 구현 (§9)** — dnsmasq 등 오픈소스도 쓰지 않는다 |

DNS는 외부 서비스를 쓰고 있지 않다. 방침 확정: 오픈소스 DNS 서버(dnsmasq 등) 운영이
아니라 **프로토콜을 직접 구현**한다. 필요한 것은 풀 리커시브 리졸버가 아니라 권한
(authoritative) 서버의 최소 기능 — UDP/53으로 질의를 받아 `DnsRecordRegistry`의
A/CNAME 레코드로 응답(RFC 1035 기본 질의/응답 + 와일드카드) — 이므로 구현 범위가
현실적이다.

## 3. 통합 모델

### 3.1 개념은 두 개뿐이다

```text
Agent : 어디서든(SOLID VM, Colab, 집 PC) 실행되는 하나의 프로그램.
        Relay에 상시 연결을 맺고 → 자기 옆의 서비스 목록을 report → 명령을 수신.

Relay : AgentList를 관리하고 트래픽을 중계하는 서버.
        각 Agent 아래에 Service(host, port, type, status)가 붙고,
        사용자는 포털에서 항목별로 [open] / [close] 한다.
```

```text
   사용자/외부 ──HTTP──▶ ┌─────────── Relay (dku-relay) ───────────┐
                         │  공개 엔드포인트  {name}.relay 또는 /t/{name}
                         │  AgentList                              │
                         │  ├─ agent-1 (SOLID_VM, vm-xxx, online)  │
                         │  │    └─ service: web:8080   [open]     │
                         │  ├─ agent-2 (COLAB, online)             │
                         │  │    └─ service: jupyter:8888 [open]   │
                         │  └─ agent-3 (EXTERNAL_PC, offline)      │
                         │       └─ service: api:3000   [close]    │
                         └──────────▲──────────▲──────────▲────────┘
                     상시 연결 1개 = 제어(등록·report·open/close) + 데이터(터널 스트림)
                         ┌──────────┴───┐  ┌───┴────┐  ┌──┴─────┐
                         │ SOLID VM     │  │ Colab  │  │ 집 PC  │
                         │ ./agent      │  │ ./agent│  │ ./agent│
                         └──────────────┘  └────────┘  └────────┘
```

### 3.2 도메인 모델

```text
Agent
├─ agentId        : 고유 ID
├─ ownerId        : 학번 (SOLID 계정, 소유권 격리 단위)
├─ location       : SOLID_VM | COLAB | EXTERNAL   ← 인바운드/아웃바운드를 대체하는 "속성"
├─ instanceId     : location=SOLID_VM일 때 CloudStack VM ID (소유권 검증)
├─ agentToken     : 영속 토큰 (등록 토큰 rt- 를 교환해 발급)
├─ connectionState: 상시 연결 상태 (연결 끊김 = 즉시 offline)
└─ services[]     : 이 Agent가 보고한 서비스 목록
     Service
     ├─ name, type      : JUPYTER | HTTP_API | WEB | ...
     ├─ host, port      : Agent 로컬 기준 주소
     ├─ status          : REGISTERED | OPEN | CLOSED | ERROR
     ├─ publicName      : open 시 배정되는 공개 이름 → Relay 엔드포인트 주소
     ├─ serviceToken    : Jupyter token 등 유형별 접속 정보
     ├─ accessPolicy    : 단국대 내부 전용(기본) / 허용 대상 지정 — Relay가 시행
     └─ expiresAt       : 만료 (Colab 런타임 수명 등)
```

핵심: **인바운드/아웃바운드 구분이 스키마에서 사라지고 `location` 속성이 된다.**
자원 유형별 차이(Colab은 Jupyter 토큰, HTTP는 헬스체크)는 코어를 분기하지 않고
`type`별 어댑터/메타데이터로 처리한다. SSH/DB 같은 유형도 코어 수정 없이 추가된다.

### 3.3 open의 의미 — 이제 위치와 무관하게 완전히 동일하다

open = "Relay 공개 엔드포인트에 이 서비스를 연결하라"는 하나의 명령.
자체 터널(§5)에서는 Agent 위치가 어디든 **같은 메커니즘**으로 이행된다:
Relay가 상시 연결로 OPEN 명령을 내리고, 이후 그 서비스로 향하는 요청을
같은 연결의 데이터 스트림으로 Agent에 전달하면, Agent가 `host:port`로 프록시한다.

- 기존 "인바운드 공개" = SOLID VM Agent의 서비스를 open (외부 사용자가 Relay 경유 접근)
- 기존 "아웃바운드 연결" = 외부 Agent의 서비스를 open (SOLID VM이 Relay 경유 접근)

접근하는 쪽이 누구냐만 다를 뿐, Relay·Agent의 동작은 문자 그대로 같다.

포털 UI는 사용자 목적별 화면("SOLID 서비스 공개" / "외부 자원 연결")을 유지해도 된다.
**통합되는 것은 백엔드 모델과 프로토콜이지, 사용자 언어가 아니다.**

## 4. 프로토콜 — 단일 Agent 생명주기

모든 Agent가 위치와 무관하게 같은 절차를 따른다.

```text
1. 발급   포털(SOLID 인증 slk-) → POST /api/agents/registration-token
          → 단기·단일사용 rt- 토큰 (Grant: ownerId, location, type, name)
2. 실행   ./agent --token rt-xxxx        (SOLID VM에서는 --config 파일도 가능)
3. 등록   Agent → POST /api/agents/register (rt- 소비 → 영속 agentToken 발급)
4. 연결   Agent → Relay 상시 연결 수립 (WebSocket, agentToken 인증)
5. 보고   연결 위 메시지: SERVICE_REPORT (서비스 목록·상태)
6. 명령   Relay → Agent: OPEN / CLOSE  (push, 즉시)
7. 중계   Relay ↔ Agent: 터널 데이터 프레임 (§5)
```

보안 원칙(기존 유지):
- SOLID 비밀번호·장기 키를 외부 환경에 저장하지 않는다 → 단기 `rt-` 교환 방식
- `rt-`는 단일 사용·TTL 수 분, Grant의 ownerId/type을 신뢰하고 클라이언트 body는 불신
- agentToken은 소유자에 묶이고 철회 가능

상시 연결은 성능 옵션이 아니라 **데이터 플레인의 전제 조건**이다. 다만 마이그레이션
중에는 기존 HTTP heartbeat 폴링(M1, 명령은 heartbeat 응답에 실어 전달)을 유지하고,
자체 터널 도입(M4)과 함께 WebSocket으로 승격한다 (§7).

## 5. 자체 터널 데이터 플레인 (Cloudflare 대체)

ngrok/frp가 하는 일을 직접 구현한다. 원리는 단순하다:

```text
외부 사용자                Relay                        Agent (NAT 뒤)
    │  HTTP 요청             │                              │
    │  {name}.relay 접속 ───▶│  name → (agent, service) 매핑 │
    │                        │  상시 연결로 스트림 개설 ────▶│
    │                        │  요청 바이트 프레이밍 전달 ──▶│ localhost:port로 프록시
    │◀── 응답 ───────────────│◀── 응답 프레임 ──────────────│
```

- **연결 방향은 항상 Agent → Relay** (아웃바운드)이므로 NAT/방화벽을 통과한다.
  이것이 quick tunnel이 하던 역할의 전부이며, 직접 구현이 가능한 이유다.
- 상시 연결 1개 위에 **스트림 멀티플렉싱**: 스트림 ID를 붙인 프레임
  (`OPEN_STREAM / DATA / CLOSE_STREAM`)으로 동시 요청 N개를 한 연결로 처리.
- 공개 주소 방식: 경로 기반(`relay/t/{name}`)이 구현이 쉽고, 서브도메인 기반
  (`{name}.relay.dku`)이 서비스 호환성이 좋다(쿠키·절대경로 문제 없음).
  서브도메인 방식은 자체 DNS(와일드카드)와 결합 — DNS 서비스가 코어의 첫 소비자가 된다.
- 1차 범위는 **HTTP(WebSocket 포함) 프록시**로 제한한다. SSH/DB 등 raw TCP는
  같은 프레이밍으로 확장 가능하지만 접근 통제 설계 후 추가한다.

### 자체 구현으로 얻는 것

1. **접근 정책 시행점 확보** — quick tunnel URL은 아는 사람 누구나 접근 가능해
   통제 불가였다. 모든 트래픽이 Relay를 지나므로 "단국대 내부 전용", 허용 대상 지정,
   SOLID 인증 연동을 **Relay에서 직접 시행**할 수 있다. 계획만 있던 접근 정책이 구현 가능해진다.
2. **외부 의존 제거** — quick tunnel의 임의 URL 변경, 속도 제한, 서비스 정책에서 자유로워진다.
3. **관측 가능성** — 트래픽이 Relay를 지나므로 요청 수·레이턴시·전송량을 직접 측정할 수
   있다. 지표 탭(§8)의 데이터가 공짜로 나온다.

### 대가 (설계로 감당해야 할 것)

1. **Relay가 데이터 플레인이 된다** — 수용량(동시 스트림, 처리량)이 제품의 핵심 스펙이
   된다. 성능 측정이 부가 과제가 아니라 필수 과제가 되는 이유.
2. **Relay의 공개 접점 필요** — 외부 사용자가 Relay에 직접 닿아야 한다.
   Railway는 HTTP/WebSocket을 지원하므로 초기 개발은 가능하나, 최종적으로는
   공인 IP(또는 포트포워딩)를 가진 SOLID VM에 Relay를 올리는
   [relay-on-vm.md](relay-on-vm.md) 트랙이 사실상 전제가 된다 (해당 문서의
   Named Tunnel 전제는 자체 터널로 대체하도록 수정 필요).
3. **TLS 종단** — Cloudflare가 해주던 HTTPS를 Relay가 직접 처리해야 한다
   (인증서 관리 또는 학내망 한정 HTTP 등 정책 결정 필요).

## 6. API 통합과 마이그레이션 경로

### 6.1 목표 API (단일)

```text
POST   /api/agents/registration-token   rt- 발급 (SOLID 인증, location·type 지정)
POST   /api/agents/register             rt- → agentToken 교환
WS     /api/agents/connect              상시 연결 (제어 메시지 + 터널 프레임)
GET    /api/agents                      내 AgentList (포털)
POST   /api/agents/{id}/services/{sid}/open    open (publicName 배정)
POST   /api/agents/{id}/services/{sid}/close   close
DELETE /api/agents/{id}                 Agent 철회
ANY    /t/{name}/**  (또는 {name}.relay.dku)   공개 터널 엔드포인트
```

### 6.2 기존 API 수렴

| 기존 | 처리 |
|---|---|
| `/api/agents/*` (VM Agent) | **통합 API의 모체.** location=SOLID_VM 케이스로 흡수 |
| `/api/resources/*` (feat/external-resource) | location=COLAB/EXTERNAL 케이스로 흡수. 컨트롤러 통합 |
| `/api/connections` | 수동 등록 = "Agent 없는 정적 Service"로 모델링하거나 입력 폼만 유지 |
| `/api/session/*` | **당분간 유지** (기존 `slink connect` 호환). 내부적으로 통합 모델에 위임하고, CLI 이전 후 단계적 폐지 |

레지스트리도 `VmAgentRegistry` + `ExternalResourceRegistry` → 단일 `AgentRegistry`로
수렴한다. 영속화는 기존 atomic 파일 패턴 재사용.

### 6.3 마이그레이션 단계

```text
M1. 통합 도메인 모델(Agent/Service) + 단일 AgentRegistry   ← 완료 (2026-07-03)
    — 기존 4개 API는 어댑터로 유지, 전송은 아직 HTTP 폴링 + Cloudflare (회귀 없음)
    — 이 시점부터 baseline 측정 시작 (§8)
M2. 포털을 통합 AgentList API로 전환 (UI 탭 구성은 유지)
M3. slink CLI(agent)를 단일 바이너리로: --location 자동 감지, 공통 프로토콜
M4. 자체 터널 도입: WebSocket 상시 연결 + 스트림 멀티플렉싱
    — Cloudflare 경유와 병행 운영하며 성능·안정성 비교 측정
M5. Cloudflare 제거, Relay를 SOLID VM으로 이전 (relay-on-vm 트랙 합류)
M6. /api/session → 통합 모델 위임, sk-dku- 키 단계적 폐지
```

## 7. 전송 계층: 폴링 → WebSocket

| 항목 | HTTP 폴링 (M1~M3) | WebSocket 상시 연결 (M4~) |
|---|---|---|
| open 명령 전파 | 다음 heartbeat까지 대기 (최악 주기만큼) | 즉시 push (ms 단위) |
| 생존 감지 | 1~2 주기 지나야 오프라인 판정 | 연결 끊김 즉시 감지 |
| 요청당 오버헤드 | 매번 TCP+TLS+HTTP 헤더 | 최초 1회 핸드셰이크, 이후 프레임 몇 바이트 |
| 터널 데이터 | 불가 (Cloudflare 필요) | 같은 연결로 중계 (자체 터널 가능) |
| Relay 상태 | 무상태 (단순) | 연결 상태 관리·재연결 백오프·ping/pong 필요 |

폴링 단계를 먼저 완성해 **before 수치를 측정해 두고**, 전환 후 개선 폭을 정량
비교하는 것이 데모 전략이다.

## 8. 성능·지표 — product 요구사항

Relay가 데이터 플레인이 되므로 지표는 부가 기능이 아니라 제품 스펙이다.
포털에 **지표 탭**을 두고 노출한다.

### 8.1 실시간 지표 (`/api/metrics`)

- Agent 수 / 온라인 수 (location별 분포), Service 수 / open 수
- 활성 터널 스트림 수, 초당 요청 수, 전송 바이트
- 명령 전파 지연(open 클릭 → Agent 이행), heartbeat/메시지 처리율
- Relay 업타임, JVM 메모리·CPU (Spring Boot Actuator)

### 8.2 핵심 벤치마크

| 지표 | 질문 | 측정 방법 |
|---|---|---|
| open 명령 전파 지연 | 클릭 → 터널 열림까지? | 폴링(M1) vs WebSocket(M4) 비교 |
| 터널 경유 레이턴시·처리량 | 직접 연결 대비 오버헤드는? | 동일 서비스: 직접 vs Cloudflare 경유 vs 자체 Relay 경유 3자 비교 |
| Relay 수용량 | Agent·동시 스트림 몇 개까지? | 가상 Agent N개(100/500/1000) + 동시 요청 부하에서 CPU·메모리·응답시간 곡선 |
| 연결 오버헤드 | 폴링 vs WS 비용 | Agent 100개 기준 네트워크 바이트·Relay CPU 비교 |

특히 "직접 vs Cloudflare vs 자체 Relay" 3자 비교는 M4의 병행 운영 기간에만 측정
가능하므로 그 시기를 놓치지 않는다. 측정치는 지표 탭의 "벤치마크" 영역에 게시한다.

## 9. 다른 서비스와의 결합

단일 솔루션은 코어이고, 다른 서비스는 그 위에 얹히는 소비자다.

- **DNS (직접 구현 — 응답기 구현 완료 2026-07-08, 와일드카드는 후속)**:
  open된 Service의 `publicName`에 이름을 붙이는 결합 레이어.
  `MockDnsProvider`를 자체 구현 DNS 서버로 교체한다 — UDP/53 리스너가
  `DnsRecordRegistry`를 그대로 데이터 소스로 사용하는 권한 서버(RFC 1035 기본
  질의/응답, A/CNAME, 와일드카드). 서브도메인 방식 채택 시 와일드카드 레코드로
  터널 엔드포인트와 직접 결합. 레코드 관리 API·소유권 검증은 이미 구현돼 있으므로
  응답기(responder)만 추가하면 된다. 이름 정책(전역 유일=선착순 vs 학번 네임스페이스)은
  [dns-naming-policy.md](dns-naming-policy.md)의 논의를 따른다.
- **접근 정책**: Service 단위 속성(`accessPolicy`)으로, 모든 트래픽이 Relay를
  지나므로 Relay가 시행점이 된다.
- **Service Portal**: AgentList의 뷰. 목적별 탭(공개/연결)은 유지하되 데이터 소스는 하나.

## 10. 확장 — Relay 다중화 (검토 항목)

데이터 플레인이 되면 다중화가 현실적 질문이 된다. WebSocket 연결이 특정 인스턴스에
귀속되므로 설계가 얽힌다.

- AgentList 공유: 공유 저장소(DB/Redis) vs Relay 간 동기화
- 라우팅: "Agent X는 어느 Relay에 붙어 있나" → 요청을 해당 인스턴스로 포워딩
- 진입점 분배: DNS 라운드로빈 / 로드밸런서
- 현 단계 결론: **Relay 1대 + 수용량 측정이 먼저.** 측정 결과(§8.2)가 다중화
  필요성을 판단하는 근거가 된다.

## 11. 지켜야 할 원칙 (요약)

1. 새 자원 유형·기능은 코어(Agent/Service 모델)를 복제하지 않고 type 어댑터·속성으로 추가한다
2. 기존 `slink connect`·Colab 흐름을 깨지 않고 어댑터로 감싼 뒤 단계적 이전한다
3. 외부 환경에는 단기 등록 토큰만 전달하고, 영속 토큰은 소유자에 묶어 철회 가능하게 한다
4. 외부 터널 서비스에 의존하지 않는다 — 전환기에만 Cloudflare를 병행하고 M5에서 제거한다
5. 개선 주장은 측정으로 증명한다 — 전환 전 baseline을 먼저 기록한다
6. UI의 사용자 언어(공개/연결)와 백엔드의 통합 모델을 혼동하지 않는다
