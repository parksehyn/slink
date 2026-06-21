# 내부 DNS 연동 요구사항

> 작성일: 2026-06-12
> 관련 문서: [service-portal-design.md](service-portal-design.md), [roadmap.md](roadmap.md), [demo-theory.md](demo-theory.md)

`INTERNAL` 접근 범위(SOLID 내부망 공유)를 **실제로 동작**시키기 위해 필요한 권한·정보·결정 목록.
미팅에서 교수님/운영팀에 요청할 항목으로 사용한다.

## 0. 현재 상태

- 코드의 DNS 연동은 인터페이스로 분리되어 있다.
  ```java
  public interface DnsProvider {
      void createRecord(String hostname, String ip);
      void updateRecord(String hostname, String ip);
      void deleteRecord(String hostname);
  }
  ```
- 현재는 `MockDnsProvider`가 로그만 남기는 모의 구현이다 (`[DNS-MOCK] Would create: ...`).
- 서비스 생성·이름변경·삭제 시 이 인터페이스를 호출하는 **지점은 이미 구현**되어 있다.
- 따라서 아래 항목이 정해지면 `MockDnsProvider`를 실제 구현(예: `PowerDnsProvider`)으로 교체하면 된다.

```text
INTERNAL의 목표 동작:
클라이언트(VPN 사용자/다른 VM) → 내부 DNS 조회 → 10.0.X.X:포트
VM IP가 바뀌어도 이름(team-demo.32211690.solid.internal)은 유지, DNS 레코드만 갱신
```

> **참고: 권한 없이 가능한 자체 테스트.** 아래 A·B·C(특히 OpenVPN push, CloudStack)는 *전교 배포*(VPN 사용자 전체 적용, IP 자동 갱신)에 필요한 것이고,
> **본인이 가진 VM들끼리** `solid.internal`을 실제로 해석시키는 검증은 운영팀 권한 없이 지금 바로 할 수 있다(같은 사설망 + 53 포트만 열면 됨).
> 실행 가능한 CoreDNS 테스트 베드 → [`../deploy/dns/README.md`](../deploy/dns/README.md).

## A. 권한·접근 (운영팀 요청 — 가장 중요)

이것이 없으면 코드가 완성돼도 동작하지 않는다.

### A-1. 내부 DNS 존(zone) 관리 권한
- `*.solid.internal`(또는 합의된 도메인) 존의 레코드를 **추가/수정/삭제**할 수 있는가?
- 기존 SOLID DNS 서버에 존을 **위임**받을지, 우리가 **별도 DNS 서버**를 세워도 되는지.

### A-2. 클라이언트가 그 DNS를 바라보게 만들 권한 ← 핵심 난관
- **SOLID OpenVPN**이 접속자에게 우리 DNS 서버를 push 할 수 있는가?
  (`push "dhcp-option DNS <ip>"`)
- **SOLID VM**들이 그 DNS를 resolver로 쓰도록 설정 가능한가?
  (`/etc/resolv.conf` 또는 DHCP 옵션)
- → 이것이 안 되면 이름을 만들어도 **아무도 그 이름을 해석하지 못한다.**

### A-3. VM IP 정보 소스 = CloudStack API
- VM의 현재 사설 IP를 **조회/추적**할 수 있는가? (IP 변경 시 레코드 자동 갱신에 필요)
- VM **소유권 검증** 가능 여부 (보안: 사용자가 신고한 instanceId·privateIp 검증).

## B. 기술 정보 (구현 방향 결정)

### B-1. DNS 서버 기술 선택
- 우리가 띄울 경우: **CoreDNS / PowerDNS / BIND** 중 무엇?
  (API로 레코드 조작이 쉬운 것 권장 — 예: PowerDNS REST API, CoreDNS + etcd)
- 기존 SOLID DNS에 위임할 경우: 그 서버가 **동적 업데이트(RFC 2136 nsupdate)** 또는 **관리 API**를 지원하는가?

### B-2. DNS 서버의 네트워크 위치
- VPN 사용자와 VM **양쪽에서 도달 가능한 곳**(사설망 내 또는 VPN 게이트웨이 근처)에 있어야 한다.
- Relay(Railway, 공인)에서 이 **사설 DNS 서버로 레코드를 어떻게 밀어넣을지**:
  VPN 경유? 사설망 안의 별도 동기화 컴포넌트?

### B-3. 이름 규칙(존 스키마) 확정
- 현재 설계: `{서비스}.{학번}.solid.internal`
- 이대로 갈지, 소유자 단위 충돌 방지 규칙 확정.

## C. 운영 정책 결정

### C-1. VM 간 / VPN 사용자 → VM 임의 포트 접근 허용 여부
- DNS로 이름이 풀려도 **방화벽이 포트를 막으면** 무의미하다.

### C-2. 내부 서비스 운영 정책
- 학생별 내부 서비스 개수, TTL, 이름 네임스페이스 정책.

## 요약표 — 필요한 것 / 담당 / 없으면

| 필요한 것 | 누구에게 | 없으면 |
|-----------|----------|--------|
| `.solid.internal` 존 레코드 관리 권한 | 운영팀 | 레코드를 만들 수 없음 |
| OpenVPN·VM이 우리 DNS를 보게 설정 | 운영팀 | 이름 해석이 안 됨 |
| CloudStack API (VM IP·소유권) | 운영팀 | IP 자동 갱신·소유권 검증 불가 |
| DNS 서버 기술·위치 결정 | 우리 + 운영팀 | 구현 방향을 못 정함 |
| VM 간 포트 접근 정책 | 운영팀 | 이름이 풀려도 접속 차단 |

## 코드 관점 — 한 것 vs 남은 것

- ✅ `DnsProvider` 인터페이스 분리, 생성/이름변경/삭제 시 호출 지점 구현 완료
- ⬜ A·B 확정 후 `MockDnsProvider` → 실제 구현(예: `PowerDnsProvider`, REST API 호출)으로 교체
- ⬜ IP 변경 감지(CloudStack 폴링 또는 VM Agent가 자기 IP 보고) → `updateRecord` 호출

## 미팅용 한 문장

> 내부 DNS는 코드 추상화는 끝났고, 실제로 켜려면
> **① `.solid.internal` 존 관리 권한, ② OpenVPN·VM이 우리 DNS를 바라보게 하는 설정 권한,
> ③ VM IP를 조회할 CloudStack API**, 이 세 가지 접근만 열어주시면 됩니다.
> 기술 스택(PowerDNS 등)과 서버 위치는 그 권한 범위가 정해지면 바로 정하겠습니다.
</content>
