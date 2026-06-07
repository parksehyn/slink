# slink 기능 로드맵

> 최종 업데이트: 2026-06-08  
> 상세 설계: [SOLID Service Portal 설계](service-portal-design.md)

## 제품 방향

현재 `slink`는 Colab이 만든 Cloudflare Tunnel 정보를 Relay가 중개하여 SOLID VM에서 Colab GPU를 사용하도록 돕는다.

다음 단계에서는 DNS와 역방향 터널을 개별 기능으로 제공하지 않고, 학생이 자신의 VM 서비스를 등록하고 접근 범위를 선택하는 **SOLID Service Portal**로 확장한다.

```text
현재:
Colab 서비스 → Cloudflare Tunnel → SOLID VM에서 사용

확장:
SOLID VM 서비스 → 내부 DNS 또는 Cloudflare Tunnel → 팀·외부에서 사용
```

학생은 DNS와 터널을 직접 관리하지 않는다.

```text
VM 선택 → 포트 입력 → 접근 범위 선택 → 주소 공유
```

## 핵심 기능

### 기존 기능 유지

- Colab JupyterLab용 Cloudflare Quick Tunnel 생성
- Relay 세션 등록·조회·삭제
- `slink connect`를 통한 Colab GPU 연결
- 사용자 API Key 인증 및 12시간 세션 TTL

### Service Portal

- 본인이 등록한 서비스 목록과 상태 조회
- VM, 포트, 서비스 이름을 선택하여 서비스 등록
- `PRIVATE`, `INTERNAL`, `TEAM`, `PUBLIC` 접근 범위 관리
- 외부 공개 TTL 설정과 즉시 종료
- 관리자용 공개 서비스 조회 및 차단

### 내부 서비스 주소

- 서비스 등록 시 기억하기 쉬운 내부 이름 제공
- VM IP 변경 시 이름은 유지하고 DNS 레코드 갱신
- VM 삭제 시 관련 레코드 정리
- 팀 및 네트워크별 조회 권한 적용

내부 DNS 적용에는 SOLID OpenVPN 클라이언트와 VM의 DNS 설정을 관리할 권한이 필요하다.

### 외부 공개

- SOLID VM Agent가 Cloudflare Tunnel을 outbound로 생성
- 외부 사용자는 OpenVPN 없이 공개 URL로 접근
- VM의 공인 IP와 외부 인바운드 포트 개방 불필요
- 초기에는 HTTP/HTTPS와 Cloudflare Quick Tunnel만 지원

Quick Tunnel은 임의의 `trycloudflare.com` 주소를 발급한다. 고정된 공개 도메인은 Named Tunnel 또는 자체 Gateway 도입 이후 지원한다.

## 목표 아키텍처

```text
                                  ┌─ Internal DNS ─→ VM private IP:port
[Student] ─→ [Service Portal] ─→ [Relay / Service Registry]
                     │            └─ Public URL / tunnel status
                     │
                     └─→ [VM Agent] ─→ Cloudflare Tunnel ─→ Internet user
                              │
                              └─→ localhost service
```

기존 Colab Session API와 새로운 Service Registry는 같은 Relay 서버에서 시작하되, 모델과 API 경계를 분리한다.

## 구현 단계

### 0. 사전 검증

- [x] SOLID VM 외부 DNS 조회 가능 확인
- [x] SOLID VM 외부 HTTPS `443/TCP` 연결 가능 확인
- [ ] SOLID VM에서 Cloudflare Quick Tunnel 왕복 테스트
- [ ] VM 간 임의 포트 및 VPN 사용자에서 VM 임의 포트 접근 확인
- [ ] 내부 DNS 설정 권한과 운영 정책 확인
- [ ] CloudStack API의 VM 목록·소유권 조회 가능 여부 확인
- [ ] 학생 서비스 외부 공개에 대한 운영 정책 확인

### 1. Service Registry API ✅

- [x] `Service` 모델 및 저장소 구현 (`ServiceEntry`, 인메모리)
- [x] 서비스 CRUD API 구현 (`/api/services/*`)
- [x] **서비스 소유권 검사** — 인증된 사용자 이메일 기준. CloudStack VM 소유권 검증은 미구현 (이슈 #3 참조)
- [x] 이름, IP, 포트, 접근 범위 입력 검증
- [x] PUBLIC scope는 create/update에서 직접 지정 불가 — `POST /publish`만 허용
- [x] publish/unpublish 시 scope 복원 (pre-publish scope 저장)
- [x] 기존 Session/User API 회귀 테스트 추가
- [x] `DnsProvider`·`TunnelProvider` 인터페이스 분리 + 모의 구현
- [x] `TunnelProvider.openTunnel`에서 privateIp 제거 — 실제 연결은 VM Agent의 loopback으로 제한

> **⚠️ 보안 제약 (미구현)**
> - VM 인스턴스 소유권: CloudStack API 또는 VM Agent 인증이 없으므로 현재 사용자가 입력한 `instanceId`와 `privateIp`는 검증되지 않는다.
> - 실제 `TunnelProvider`는 VM Agent 검증 또는 CloudStack API 연동 이전에 활성화하지 않는다.
> - 사용자 계정: 이메일/학번 자기 신고 방식이므로 SOLID SSO·LDAP 연동 이전에는 대역 외 계정 탈취가 가능하다.

### 2. 웹 포털 MVP ✅

- [x] 서비스 목록 화면 (`/portal/index.html`)
- [x] 서비스 등록 화면 (`/portal/add.html`)
- [x] 서비스 상세 및 접근 범위 변경 화면 (`/portal/detail.html`)
- [x] 외부 공개 시작·종료 화면
- [x] 상태, TTL, 내부 주소, 공개 URL 표시 (내부 DNS 모의 구현 명시; 외부 공개는 VM Agent 실제 동작)
- [x] PENDING 상태 UI — VM Agent 실행 안내 표시 및 10초 자동 갱신

초기에는 별도 Service Portal로 제공한다. 기존 SOLID Cloud 관리 화면 직접 통합은 운영 플랫폼 수정 권한 확보 이후 검토한다.

### 3. VM Agent와 외부 공개 ✅ (MVP)

- [x] VM Agent 등록 및 heartbeat (`POST /api/agents/register`, `/heartbeat`)
- [x] Cloudflare Quick Tunnel 생성·종료 (Agent가 cloudflared 실행)
- [x] 터널 URL 보고 → Service Registry에 PUBLIC 상태 갱신
- [x] Agent 종료 및 TTL 만료 시 자동 정리 (CLOSE_TUNNEL 명령)
- [x] `slink agent start --instance-id solid-XXXX` CLI
- [ ] 로컬 HTTP 포트 상태 확인 (서비스가 실제로 listen 중인지)
- [ ] 학생별 공개 서비스 개수 제한
- [ ] Agent 재시작 시 기존 터널 복원 (영속 상태)

### 4. 내부 DNS

- [ ] 내부 DNS 서버 기술 선택 및 배포
- [ ] 서비스 생성·변경·삭제와 DNS 레코드 연동
- [ ] OpenVPN 클라이언트와 SOLID VM의 DNS 설정 연동
- [ ] 팀 및 Private Network별 조회 권한 적용
- [ ] VM IP 변경과 삭제에 따른 자동 갱신

### 5. 운영 기능

- [ ] 관리자용 공개 서비스 현황
- [ ] 서비스 강제 종료와 감사 로그
- [ ] 트래픽 및 요청 제한
- [ ] 장애 상태와 Agent 연결 상태 표시
- [ ] 영속 저장소 도입
- [ ] 고정 공개 도메인 또는 자체 Gateway 검토

## 현재 구현 완료 요약

1단계(Service Registry API), 2단계(웹 포털 MVP), 3단계(VM Agent 외부 공개 MVP)가 완료되었다.

```text
완료:
- 기존 Colab 연결 기능 유지
- 서비스 등록·조회·수정·삭제
- PENDING 기반 publish 흐름 (publish → PENDING → Agent TUNNEL_READY → PUBLIC)
- VM Agent CLI (slink agent start) — 실제 Cloudflare Quick Tunnel 동작
- owner 기반 Agent 격리 (instanceId + ownerId 복합 키)
- orphan CLOSE_TUNNEL — 서비스 삭제 후에도 터널 종료 명령 전달
- PENDING 상태 포털 UI, 10초 자동 갱신
- 통합 테스트 49개

남아있는 것:
- 실제 내부 DNS 배포 — SOLID 운영 환경의 DNS 설정 권한 확인 후 진행
- CloudStack API 연동 (VM 소유권 검증)
- 영속 저장소 (재시작 시 서비스 목록 유지)
- 학생별 공개 서비스 개수 제한
```

다음은 4단계(내부 DNS)와 5단계(운영 기능)이며, 교수님 미팅에서 운영 정책·자원 확보 논의 후 진행한다.
