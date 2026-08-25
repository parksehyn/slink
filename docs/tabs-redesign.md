# 도메인 / 터널링 탭 재구성 설계

> 상태: 확정 설계 — DNS 탭 + 터널링 탭(서비스/연결/Agent) 구현 완료, **인증은 SOLID 세션(`slk-`)으로 통일됨**(2026-06-24).
> 작성일: 2026-06-14
> 관련 문서: [service-portal-design.md](service-portal-design.md), [roadmap.md](roadmap.md), [internal-dns-requirements.md](internal-dns-requirements.md), [demo-theory.md](demo-theory.md)
>
> ⚠️ 이 문서의 일부 인증 묘사는 초기 `sk-dku-` 기준일 수 있다. 현재 인바운드 서비스·아웃바운드 연결·VM Agent 등록은
> 모두 SOLID 인증(`slk-`, 소유자=학번)이며 인바운드 서비스는 vmId 기반(서버가 사설 IP·소유권 채움)이다.
> 최신 구현 현황은 [progress.md](progress.md) "터널링 SOLID 인증 통일" 절 참고.

## 1. 배경

현재 포털은 **서비스 등록 → DNS/터널 자동 파생** 구조다.
사용자가 서비스(VM·포트·범위)를 등록하면 내부 DNS 레코드가 자동 생성되고, DNS 탭은 그것을
읽기 전용으로 보여준다. 외부 공개는 `publish`로 VM Agent가 Cloudflare Quick Tunnel을 연다.

이번 재구성은 기능 축을 **도메인(DNS)** 과 **터널링** 두 갈래로 명확히 갈라, 각각을 독립 탭으로
제공한다. 또한 지금까지 사람이 직접 입력하던 사설 IP·VM명을 SOLID API로 자동 조회한다.

## 2. 확정 결정사항

미팅/피드백에서 확정된 4가지.

| # | 주제 | 결정 |
|---|------|------|
| 1 | SOLID API (VM 자동조회) | **Mock으로 진행** — `SolidApi` 인터페이스 + `MockSolidApi`. 키 확보 시 실제 구현 교체 |
| 2 | DNS 레코드 관리 방식 | **수동 직접관리 (상용 DNS식)** — 독립 레코드 편집기. 서비스-파생 자동 DNS는 분리/제거 |
| 3 | 일반 터널링 | **일반화한 새 기능** — 임의 외부 서비스 터널 인입. 기존 Colab 세션은 그 특수 케이스 |
| 4 | 이번 작업 범위 | **설계 문서 + 구현 플랜까지** (코드는 본 문서 이후 단계) |

`DnsProvider`·`TunnelProvider`처럼 외부 연동은 인터페이스로 분리하고 현재는 모의 구현을 쓴다는
프로젝트 원칙을 그대로 따른다. `SolidApi`도 동일 패턴이다.

## 3. 탭 구조

```
Service Portal
├─ 도메인 서비스 (DNS)   ← 내부 DNS 레코드 직접 관리 (A / CNAME)
└─ 터널링                ← 외부 연결: [역방향] 우리 VM 공개 / [일반] 외부 서비스 인입
```

- 기존 `Service Portal` / `DNS Records` 사이드바 항목을 위 두 탭으로 재편한다.
- 기존 서비스 목록(`index.html`)·상세(`detail.html`)는 유지하되, **INTERNAL scope의 DNS 자동생성
  호출만 분리**한다. DNS는 도메인 탭에서 독립적으로 관리한다.

## 4. 도메인 서비스 탭 (DNS)

### 4.1 목표 UX — 상용 DNS 콘솔식

Cloudflare/Route53/가비아 DNS 콘솔처럼 **레코드를 직접 한 줄씩** 추가·수정·삭제한다.
별도 추가 모달 없이 **한 페이지에서 인라인 행 추가**한다.

```text
Name              Type    Value                          TTL    [actions]
api.myteam        A       10.0.10.89                     60     ✎  🗑
web.myteam        CNAME   api.myteam.solid.internal      60     ✎  🗑

[+ 레코드 추가]   ← 누르면 아래에 입력 행이 inline으로 생김
┌───────────────────────────────────────────────────────────────────┐
│ [api.myteam ] [A ▾] [10.0.10.89 (내 VM에서 선택 ▾)] [60] [저장][취소] │
└───────────────────────────────────────────────────────────────────┘
```

- 입력은 **A 레코드 / CNAME 레코드** 두 가지만.
- A 레코드의 Value는 **SOLID API로 가져온 내 VM 사설 IP 목록에서 선택**(드롭다운+직접입력 겸용).
  → 사람이 IP를 외워 치지 않아도 됨. (결정 #1, #2 결합)
- CNAME의 Value는 대상 호스트명(`*.solid.internal`).
- 인라인 편집(✎)도 같은 행 변환 방식.

### 4.2 데이터 모델 — 독립 `DnsRecord`

서비스에 묶이지 않는 독립 모델을 새로 둔다.

```java
class DnsRecord {
    String id;
    String ownerId;
    String name;          // zone 앞부분, 예: "api.myteam"
    DnsRecordType type;   // A, CNAME
    String value;         // A: 사설 IP / CNAME: 대상 호스트명
    int ttl;              // 기본 60
    Instant createdAt;
    Instant updatedAt;
}

enum DnsRecordType { A, CNAME }
```

- Zone은 기존과 동일하게 `solid.internal`. FQDN = `{name}.solid.internal`.
- 인메모리 저장소(`DnsRecordRegistry`)로 시작 — 기존 `ServiceRegistry`와 동일한 패턴.
- 영속화는 후속(로드맵 5단계 영속 저장소와 함께).

### 4.3 검증 규칙

- `name`: 영문소문자/숫자/`.`/`-`, zone 전체에서 **중복 불가** (DNS는 전역 네임스페이스).
- `A.value`: 사설 IP 형식(`10.0.x.x`). 가능하면 SolidApi가 반환한 내 VM IP에 한정.
- `CNAME.value`: 호스트명 형식. 동일 `name`에 A와 CNAME 공존 금지.
- 소유권: 레코드는 `ownerId` 단위로 격리하여 본인 레코드만 수정/삭제.

### 4.4 API

| Method | Path | 역할 |
|--------|------|------|
| `GET` | `/api/dns/records` | 본인 레코드 목록 |
| `POST` | `/api/dns/records` | 레코드 추가 |
| `PATCH` | `/api/dns/records/{id}` | 레코드 수정 |
| `DELETE` | `/api/dns/records/{id}` | 레코드 삭제 |

- 각 변경 시 기존 `DnsProvider`(`createRecord`/`updateRecord`/`deleteRecord`)를 호출.
  현재는 `MockDnsProvider`가 로그만 남긴다. 실연동 시 `PowerDnsProvider` 등으로 교체.
- 기존 서비스 등록 경로의 DNS 자동생성 호출은 제거(또는 비활성)한다.

## 5. 터널링 탭

방향을 **선택**하게 한다.

```text
터널링 방향
○ 역방향 (Expose)  — 우리 VM 서비스를 외부 인터넷에 공개
○ 일반 (Consume)   — 외부 서비스가 연 터널로 우리 VM이 접근 (예: Colab GPU)
```

### 5.1 역방향 터널링 (기존 publish 재사용)

우리 VM의 로컬 서비스를 외부에 공개한다. **기존 구현을 그대로 정리해 옮긴다.**

```text
외부 사용자 → https://random.trycloudflare.com → Cloudflare Tunnel → SOLID VM localhost:port
```

- 백엔드: 기존 `POST/DELETE /api/services/{id}/publish` + VM Agent + Cloudflare Quick Tunnel.
- VM 선택은 SolidApi 목록에서(결정 #1). TTL/즉시 종료 등 기존 흐름 유지.
- 신규 백엔드 없음 — UI를 터널 탭으로 재배치.

#### 접근 등급 2단계 (중요 — 경로가 다름)

"퍼미션 받은 사람만 접근"은 등급에 따라 **기술 경로가 다르다.** 둘을 혼동하지 말 것.

| 등급 | 누가 접근 | 실제 경로 | 공개 터널 필요? | 상태 |
|------|-----------|-----------|------------------|------|
| **DKU_INTERNAL (기본)** | SOLID 사설망/VPN 사용자 | **내부 DNS(사설 IP A 레코드)** — VPN 사용자가 사설 IP로 직접 접근. INTERNAL scope가 곧 이것 | ❌ 불필요 | ✅ DNS 탭으로 동작(통제=네트워크/VPN/방화벽, OpenVPN SSH와 동일) |
| **외부 허용 (ALLOWLIST 등)** | VPN 밖 지정 대상 | **공개 터널(Cloudflare Named) + Access(이메일 허용)** | ✅ 필요 | ⏳ 모델만 존재, 시행은 Phase 2(계정·고정 도메인 확보 후) |

즉 "SOLID 내부만"은 **공개 터널 없이 내부 DNS로 끝난다.** 공개 터널은 *VPN 밖 외부인*에게 열 때만 쓴다.
`AccessPolicy`/`allowedEmails`는 외부 허용 등급의 시행 모델이며 현재는 저장·표시만 한다(시행 Phase 2).

### 5.2 일반 터널링 (일반화한 새 기능, 설계만)

외부 서비스가 터널을 열어두면 우리 VM이 그 URL로 접근한다. 기존 Colab GPU 연동
(`Session` + `slink connect`)이 이 패턴의 **특수 케이스**다. 이를 일반화한다.

```text
외부 서비스(Colab 등) → cloudflared → 공개 URL → Relay 등록
SOLID VM → Relay에서 URL 조회 → 외부 서비스 사용
```

일반화 모델 제안(후속 구현):

```java
class ExternalLink {
    String id;
    String ownerId;
    String name;          // 예: "colab-gpu", "team-api"
    ExternalKind kind;    // JUPYTER(기존 Colab), HTTP, ...
    String url;           // 외부 공개 URL
    String token;         // 선택 (Jupyter 토큰 등)
    Instant expiresAt;
}
```

- 기존 `Session`은 `kind=JUPYTER`인 `ExternalLink`로 흡수 가능. **기존 Session/`slink connect`
  회귀 없이** 일반화하는 것이 목표.
- 이번 범위에서는 **UI 자리 + 모델 설계까지**. 일반 터널링 백엔드 일반화는 후속 단계.

## 6. SOLID API (Mock)

VM 사설 IP·이름을 사람이 입력하지 않고 자동 조회한다.

```java
interface SolidApi {
    List<VmInstance> listMyInstances(String ownerId);
    Optional<VmInstance> getInstance(String ownerId, String instanceId);
}

record VmInstance(String instanceId, String name, String privateIp, String state) {}
```

- `MockSolidApi`: 더미 VM 2~3개 반환(예: `solid-32211690 / 10.0.10.89`). 키 확보 시
  `CloudStackSolidApi`(CloudStack `listVirtualMachines`)로 교체.
- 노출 API: `GET /api/instances` — 내 VM 목록.
- 사용처: ① 도메인 탭 A레코드 Value 자동완성, ② 역방향 터널 VM 선택, ③ 서비스 등록 VM 선택.

## 7. 테스트 전략 (SOLID VM)

```text
VM-A : 내부 DNS 서버 역할 (CoreDNS/dnsmasq 등)
VM-B : 클라이언트 — resolv.conf를 VM-A로 → api.myteam.solid.internal 해석 → 대상 VM:port 접근
VM-C : 추가 클라이언트/대상
```

- **Mock 단계(이번 구현 범위)**: 실제 DNS 서버 없이 포털 레코드 CRUD UX와 SolidApi 자동완성만
  검증. `MockDnsProvider`는 로그만 남김.
- **실연동 단계(후속)**: VM-A에 DNS 서버 기동 + Relay→DNS 레코드 동기화(`PowerDnsProvider` 등),
  VM-B/C에서 이름 해석·포트 접근 왕복 테스트. 필요한 권한은
  [internal-dns-requirements.md](internal-dns-requirements.md) 참조.

## 8. 구현 플랜 (본 문서 이후 단계)

> 이번 턴은 7단계 중 **0(본 문서)까지**. 아래는 다음에 코드로 진행할 순서.

| 단계 | 내용 | 산출물 |
|------|------|--------|
| 0 | 설계 확정 (본 문서) | `docs/tabs-redesign.md` ✅ |
| 1 | `SolidApi` 인터페이스 + `MockSolidApi` + `GET /api/instances` | model `VmInstance`, port `SolidApi`, impl `MockSolidApi`, `InstanceController` |
| 2 | `DnsRecord` 모델 + `DnsRecordRegistry` + `/api/dns/*` CRUD | model/registry/controller/dto, `DnsProvider` 연동 |
| 3 | 서비스-파생 자동 DNS 호출 분리/제거 (Service Registry 회귀 없이) | `ServiceRegistry`에서 DNS 호출 제거 |
| 4 | 도메인 탭 UI 교체 — `dns.html`을 인라인 A/CNAME 편집기로 + VM 자동완성 | `portal/dns.html`, `portal.js` |
| 5 | 터널 탭 UI — 역방향(기존 publish 재배치) + 일반(자리/스텁) | `portal/tunnel.html`, 사이드바 재편 |
| 6 | 일반 터널링 백엔드 일반화 (`Session` → `ExternalLink`) | 후속, 기존 `slink connect` 회귀 없이 |
| 7 | 실 DNS 서버 연동 (VM-A) + Relay→DNS 동기화 | 후속, 운영 권한 확보 후 |

각 단계는 기존 Session/User/Service API **회귀 없이** 진행하고, 통합 테스트를 함께 추가한다.

## 9. 미해결 / 추후 확인

- SOLID(CloudStack) API 키 발급 경로 — 확보되면 단계 1의 `MockSolidApi`를 실제 구현으로 교체.
- 내부 DNS 실연동 권한 3종 — [internal-dns-requirements.md](internal-dns-requirements.md) 그대로.
- 일반 터널링 일반화 시 기존 `Session` 마이그레이션 범위 (호환 유지 vs 모델 통합).
- DNS 이름 네임스페이스 정책 — owner 단위 prefix 강제 여부(`{name}.{학번}.solid.internal`).
