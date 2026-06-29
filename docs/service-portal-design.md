# SOLID Service Portal 설계

> 상태: 제안 설계  
> 작성일: 2026-06-07  
> 관련 문서: [roadmap.md](roadmap.md), [design-a.md](archive/design-a.md), [progress.md](progress.md)

## 1. 배경

현재 `slink`는 Google Colab이 Cloudflare Quick Tunnel을 생성하고, Relay 서버가 연결 정보를 중개하여 SOLID VM에서 Colab GPU를 사용하도록 돕는다.

```
Colab → Cloudflare Tunnel → 공개 URL ← SOLID VM
          Relay는 URL과 세션 정보를 중개
```

SOLID Cloud VM은 `10.0.X.X` 형태의 사설 IP를 사용한다. 학생은 OpenVPN으로 SOLID 네트워크에 접속한 뒤 VM에 SSH로 접근한다. Shared Network에서는 다른 사용자의 VM과 통신할 수 있고, 별도 Private Network도 구성할 수 있다.

현재 방식에는 다음 불편이 있다.

- 학생과 팀원이 변경될 수 있는 VM IP를 직접 기억하고 공유해야 한다.
- VM에서 실행한 웹·API 서비스를 외부 발표자에게 보여주려면 별도 네트워크 도구를 직접 설정해야 한다.
- DNS, 터널, 세션을 각각 제공하면 학생이 네트워크 구현 방식을 이해하고 관리해야 한다.

## 2. 제품 목표

학생이 자신의 VM에서 실행한 서비스를 포털에 등록하고, 접근 범위만 선택하면 플랫폼이 적절한 연결 방식을 자동으로 적용한다.

> 학생이 DNS 레코드나 터널을 관리하는 것이 아니라, 서비스를 등록하고 공유한다.

학생이 결정할 항목은 다음 세 가지다.

1. 어떤 VM의 서비스인가?
2. 어떤 포트에서 실행 중인가?
3. 누구에게 공개할 것인가?

플랫폼은 내부 DNS, 외부 터널, 권한, 만료 및 정리를 담당한다.

## 3. 범위

### 포함

- 포털에서 VM 서비스 등록·조회·수정·삭제
- 서비스별 접근 범위 설정
- 내부 서비스의 고정된 이름 제공
- 공개 서비스의 임시 외부 URL 제공
- 서비스 상태 및 만료 시간 표시
- 기존 Colab 연결 기능 유지

### 초기 범위에서 제외

- SOLID 기존 CloudStack 관리 화면 직접 수정
- SSH, DB 등 임의 TCP 프로토콜의 인터넷 공개
- 자체 터널 Gateway 구축
- 사용자 지정 공개 도메인 자동 발급
- 과금 및 정교한 트래픽 분석

## 4. 핵심 개념

### Instance

학생이 소유하거나 사용할 권한이 있는 SOLID VM이다.

### Service

특정 VM의 특정 로컬 포트에서 실행되는 애플리케이션이다.

예:

```text
이름: team-demo
VM: solid-32211690
로컬 포트: 3000
프로토콜: HTTP
```

### Scope

서비스에 접근할 수 있는 범위다.

| 범위 | 접근 대상 | 연결 방식 |
|------|-----------|-----------|
| `PRIVATE` | 소유자 | 포털에만 등록, 공유 주소 없음 |
| `INTERNAL` | SOLID 네트워크 사용자 | 내부 DNS → VM 사설 IP |
| `PUBLIC` | 인터넷 사용자 | Cloudflare Tunnel → VM 로컬 서비스 |

`TEAM` 범위는 팀 계정·권한·네트워크 정책이 준비된 뒤 추가한다. DNS만으로는 팀 단위 접근 제어를 강하게 보장하기 어렵기 때문에 MVP 화면에서는 노출하지 않는다.

## 5. 사용자 흐름

### 5.1 VM과 애플리케이션 준비

1. 학생이 기존 SOLID 대시보드에서 VM을 생성한다.
2. OpenVPN 또는 VNC를 통해 VM에 접속한다.
3. VM에서 웹·API 애플리케이션을 실행한다.

```text
solid-32211690
10.0.10.89
localhost:3000에서 애플리케이션 실행 중
```

### 5.2 서비스 등록

학생이 별도 SOLID Service Portal에 로그인하고 `서비스 추가`를 선택한다.

```text
서비스 이름: team-demo
인스턴스: solid-32211690
포트: 3000
프로토콜: HTTP
접근 범위: SOLID 내부
```

플랫폼은 다음을 수행한다.

1. 사용자의 VM 접근 권한을 확인한다.
2. VM IP와 입력값을 검증한다.
3. Service Registry에 서비스를 등록한다.
4. 가능한 경우 포트 상태를 확인한다.
5. 접근 범위에 맞는 주소를 생성한다.

결과:

```text
team-demo.32211690.solid.internal:3000
```

### 5.3 내부 공유

`INTERNAL` 서비스는 SOLID VPN 사용자와 SOLID VM이 내부 주소로 접근한다.

```text
클라이언트 → 내부 DNS 조회 → 10.0.10.89:3000
```

VM IP가 변경되더라도 서비스 이름은 유지하고 DNS 레코드만 갱신한다.

### 5.4 외부 공개

학생이 서비스 상세 화면에서 `외부 공개 시작`과 만료 시간을 선택한다.

```text
공개 시간: 8시간
```

플랫폼은 VM Agent에 터널 생성을 요청한다. VM Agent는 같은 VM의 로컬 서비스로 Cloudflare Quick Tunnel을 생성하고 Relay에 공개 URL을 등록한다.

```text
외부 사용자
→ https://random.trycloudflare.com
→ Cloudflare Tunnel
→ SOLID VM localhost:3000
```

외부 사용자는 SOLID VPN이 필요하지 않으며, SOLID VM의 외부 인바운드 포트를 개방하지 않는다.

### 5.5 공개 종료 및 삭제

- `외부 공개 종료`: 터널과 공개 URL만 제거하고 내부 서비스 등록은 유지한다.
- `서비스 삭제`: 내부 DNS, 터널, 권한 및 Service Registry 정보를 모두 정리한다.
- TTL 만료 또는 Agent 연결 종료 시 공개 상태를 자동으로 비활성화한다.

## 6. 화면 구성

MVP는 기존 SOLID Cloud 관리 화면을 직접 수정하지 않고 별도 웹 포털로 구현한다. 향후 운영 플랫폼 수정 권한이 확보되면 기존 대시보드에서 포털로 연결하거나 메뉴를 통합한다.

> **설계 결정 (2026-06-21): "콘솔" 방향 채택.**
> 본래 명제는 "학생이 DNS·터널을 직접 관리하지 않고 서비스만 등록·공유"(인프라 은닉)였다.
> 그러나 실제 사용자층(랩/실습 — VM을 직접 다루고 통제권을 원함)에 맞춰,
> 포털을 **DNS 탭 / 터널링 탭**으로 나눠 인프라를 *드러내는* 클라우드 콘솔 형태로 전환한다.
> 참고 모델: **Google Cloud DNS**(존→레코드)와 **IAP/IAM**(기본 거부 + 주체 목록).
> 인바운드 접근 권한 화면은 IAP 스타일(기본 거부, 허용 주체 목록, 시행 상태 배너)을 따르며,
> 실제 시행은 Cloudflare Access(≈ IAP)로 한다(예정). [relay-on-vm.md](relay-on-vm.md)·[internal-dns-requirements.md](internal-dns-requirements.md) 참조.

### 6.1 My Services

```text
NAME        INSTANCE          PORT   SCOPE      STATUS   EXPIRES
team-demo   solid-32211690    3000   PUBLIC     ONLINE   7h 42m
api         solid-32211690    8080   INTERNAL   ONLINE   -
```

### 6.2 Add Service

```text
Service name   [team-demo        ]
Instance       [solid-32211690  v]
Local port     [3000             ]
Protocol       [HTTP           v]
Access scope   [INTERNAL       v]

[Create Service]
```

### 6.3 Service Detail

```text
team-demo                                      ONLINE

Instance       solid-32211690
Local port     3000
Internal       team-demo.32211690.solid.internal:3000
Public URL     https://random.trycloudflare.com
Expires        7h 42m

[Change Scope] [Stop Public Access] [Delete]
```

## 7. 아키텍처

```text
                                  ┌─ Internal DNS ─→ VM private IP:port
[Student] ─→ [Service Portal] ─→ [Service Registry]
                     │            └─ Public URL / tunnel status
                     │
                     └─→ [VM Agent] ─→ Cloudflare Tunnel ─→ Internet user
                              │
                              └─→ localhost service
```

### Service Portal

- 학생용 웹 UI
- 서비스 등록 및 공유 범위 관리
- Relay API 사용

### Relay / Service Registry

- 사용자, VM, 서비스, 공개 세션 정보 관리
- 서비스 소유권과 이름 중복 검증
- TTL 및 상태 정리
- 기존 Colab Session API 유지

### VM Agent

- VM 신원 등록 및 heartbeat
- 로컬 포트 상태 확인
- 공개 요청 시 `cloudflared` 실행
- 터널 URL 등록 및 종료

VM Agent가 중앙 서버의 요청을 직접 받으려면 SOLID 인바운드 접근이 필요하므로, 초기에는 Agent가 Relay를 주기적으로 조회하거나 지속적인 outbound 연결을 유지한다.

### Internal DNS

- 내부 서비스 이름을 VM 사설 IP로 해석
- VM IP 변경 및 삭제 시 레코드 갱신
- 실제 적용을 위해 SOLID OpenVPN 클라이언트와 VM이 해당 DNS 서버를 사용하도록 설정할 권한이 필요하다.

### Cloudflare Tunnel

- SOLID VM의 서비스 외부 공개
- VM에서 외부로 연결하므로 공인 IP와 외부 인바운드 개방이 필요 없다.
- Quick Tunnel은 임의의 `trycloudflare.com` URL을 발급한다.
- 고정된 사용자 지정 공개 도메인은 Named Tunnel 또는 자체 Gateway 도입 이후 지원한다.

## 8. 데이터 모델 초안

```java
class Service {
    String id;
    String ownerId;
    String teamId;
    String name;
    String instanceId;
    String privateIp;
    int localPort;
    Protocol protocol;       // HTTP, HTTPS
    Scope scope;             // PRIVATE, INTERNAL, PUBLIC (TEAM은 후속 확장)
    ServiceStatus status;    // UNKNOWN, ONLINE, OFFLINE
    String internalHostname;
    String publicUrl;
    Instant publicExpiresAt;
    Instant createdAt;
    Instant updatedAt;
}
```

초기에는 메모리 저장소를 사용할 수 있지만, 운영 단계에서는 Relay 재시작에도 서비스 정의가 유지되도록 영속 저장소가 필요하다.

## 9. API 초안

| Method | Path | 역할 |
|--------|------|------|
| `POST` | `/api/services` | 서비스 등록 |
| `GET` | `/api/services` | 본인 서비스 목록 |
| `GET` | `/api/services/{id}` | 서비스 상세 조회 |
| `PATCH` | `/api/services/{id}` | 이름·범위·만료 설정 변경 |
| `DELETE` | `/api/services/{id}` | 서비스 삭제 |
| `POST` | `/api/services/{id}/publish` | 외부 공개 요청 |
| `DELETE` | `/api/services/{id}/publish` | 외부 공개 종료 |
| `POST` | `/api/agents/register` | VM Agent 등록 |
| `POST` | `/api/agents/{id}/heartbeat` | Agent 및 서비스 상태 갱신 |

## 10. 보안 및 운영 원칙

- 사용자는 자신이 소유하거나 허가받은 VM만 등록할 수 있어야 한다.
- 서비스 이름과 내부 DNS 이름은 소유자 단위로 충돌을 방지한다.
- 초기 외부 공개는 HTTP/HTTPS 서비스로 제한한다.
- 외부 터널 대상은 해당 VM의 loopback 주소로 제한하여 다른 VM이나 SOLID 내부 시스템을 우회 접근하지 못하게 한다.
- 공개 서비스는 기본 TTL을 가지며 학생별 동시 공개 개수를 제한한다.
- VM 또는 Agent가 종료되면 공개 URL을 비활성화하고 상태를 갱신한다.
- 관리자는 공개 서비스 목록과 소유자를 확인하고 즉시 중지할 수 있어야 한다.
- 외부 터널 사용은 기술적 가능성과 별개로 SOLID 운영 정책 승인이 필요하다.

## 11. 확인된 사실과 미확인 사항

### 확인됨

- SOLID VM은 사설 IP를 사용한다.
- OpenVPN을 통해 학생 PC에서 VM에 SSH 접속하는 구조다.
- Shared Network는 다른 사용자의 VM과 통신 가능한 주소를 제공한다.
- SOLID VM에서 외부 DNS 조회와 HTTPS `443/TCP` 연결이 가능하다.
- 기존 Colab Agent가 Cloudflare Quick Tunnel과 Relay 등록 방식을 사용한다.

### 추가 확인 필요

- SOLID VM에서 Cloudflare Quick Tunnel이 실제로 연결되는가?
- Shared Network에서 VM 간 임의 포트와 VPN 사용자에서 VM 임의 포트 접근이 허용되는가?
- SOLID OpenVPN 및 VM의 DNS 서버 설정을 플랫폼이 관리할 수 있는가?
- CloudStack API로 사용자별 VM 목록과 소유권을 조회할 수 있는가?
- 외부 터널을 통한 학생 서비스 공개가 운영 정책상 허용되는가?

## 12. MVP

### MVP 1: 서비스 레지스트리와 포털

- 기존 Colab 기능 유지
- Service 모델과 CRUD API
- 별도 웹 포털의 서비스 목록·등록·상세 화면
- 실제 DNS와 터널 연동 전 상태를 명확히 표시
- 사용자 소유권 검증은 현재 API Key 기반으로 시작

### MVP 2: VM Agent 기반 외부 공개

- VM Agent 등록과 heartbeat
- 포트 상태 확인
- Cloudflare Quick Tunnel 생성·종료
- 공개 URL, TTL, 상태 관리

### MVP 3: 내부 DNS 연동

- 내부 DNS 레코드 자동 생성·갱신·삭제
- OpenVPN 및 VM DNS 설정 연동
- 팀과 네트워크별 조회 권한 적용 (후속 확장)

## 13. 성공 기준

학생이 다음 경험을 네트워크 지식 없이 완료할 수 있어야 한다.

```text
VM에서 애플리케이션 실행
→ 포털에서 VM, 포트, 접근 범위 선택
→ 생성된 내부 주소 또는 공개 URL 공유
→ 포털에서 공개 종료 및 삭제
```

내부 DNS와 역방향 터널은 각각 독립된 최종 제품이 아니라, 이 경험을 구현하는 기반 기술로 취급한다.
