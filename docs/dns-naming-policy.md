# 내부 DNS 이름 정책 — 멀티유저 충돌 방지 (미팅 논의용)

> 작성일: 2026-06-22
> 관련: [dns-api-spec.md](dns-api-spec.md), [service-portal-design.md](service-portal-design.md), [internal-dns-requirements.md](internal-dns-requirements.md)

## 배경 (문제)

여러 학생이 같은 DNS 백엔드를 쓰는데, 둘 이상이 같은 이름(예: `web`)을 만들면 같은
`web.solid.internal`로 **충돌**한다. CoreDNS zone은 이름 하나당 한 값이라, 나중에 만든 사람이
앞사람 레코드를 **덮어쓴다(하이재킹)**. → 멀티유저 실배포 전 반드시 정책이 필요하다.

## 현재 구현 (결정: **전역 유일**)

- DNS 이름은 **전체에서 유일**해야 한다(소유자 무관, 선착순). 이미 쓰는 이름이면 `DUPLICATE_RECORD`(409).
- 이름 형태: `web.solid.internal` (짧고 깔끔).
- 소유권은 별도로 유지: 본인 레코드만 수정/삭제 가능.
- 구현: `DnsRecordRegistry.nameTaken(name, excludeId)` 전역 검사.

### 장단점
- ✅ 이름이 짧고 외우기 쉬움. 학번 등 PII가 이름에 안 드러남.
- ⚠️ **이름 선점·경쟁**: 흔한 이름(`web`,`api`,`test`)을 누군가 먼저 차지하면 다른 학생은 못 씀.
- ⚠️ 운영 부담: 예약어/정리 정책, 방치된 이름 회수 정책이 있어야 함.

## 향후 검토안: **학번 네임스페이스** (논의 대상)

- 이름 `web` → 실제 `web.<학번>.solid.internal` 로 생성. 학생마다 자기 하위도메인.
- 설계문서(`service-portal-design.md`)가 제안한 `{서비스}.{학번}.solid.internal` 방식과 일치.

### 장단점
- ✅ **충돌·하이재킹·선점 원천 불가**. 학생별 격리. 운영 부담 적음.
- ⚠️ 이름이 길어지고 **학번이 DNS 이름에 노출**(준-PII). 접속 주소가 `web.32211690.solid.internal`.
- ⚠️ 학번 대신 익명 토큰(예: 짧은 해시)으로 대체할지 여부도 검토 가능.

## 비교 요약

| 항목 | 전역 유일 (현재) | 학번 네임스페이스 (검토) |
|---|---|---|
| 접속 이름 | `web.solid.internal` | `web.32211690.solid.internal` |
| 충돌/하이재킹 | 선착순으로 방지(경쟁 발생) | 구조적으로 불가 |
| 이름 선점 | 발생 | 없음 |
| PII 노출 | 없음 | 학번 노출(또는 익명 토큰 대안) |
| 코드 변경량 | 적음(완료) | `getFqdn`/zone 기록에 학번 prefix 추가 |

## 미팅 논의 포인트

1. 최종 정책: **전역 유일** 유지 vs **학번 네임스페이스** 전환?
2. 학번 노출이 문제면 → 익명 소유자 토큰 사용?
3. 전역 유일로 갈 경우: 예약어(`ns`,`www` 등) 차단, 방치 레코드 회수(TTL/만료) 정책.
4. 학생당 레코드 개수 상한.

## 상태

- ✅ 전역 유일: 구현·테스트 완료(`DnsRecordApiTest.globalUniqueName_blocksOtherUser`).
- ✅ 전역 유일 보완(선착순 부작용 방지) — 모두 `application.properties`로 조정 가능, `DnsRecordPolicyTest`로 검증:
  - 예약어(`ns`,`www` 등) 차단 → `RESERVED_NAME`(400). 목록 `dns.reserved-names`.
  - 학생당 레코드 개수 상한(기본 20) → `RECORD_LIMIT_EXCEEDED`(429). `dns.max-records-per-owner`.
  - 방치 레코드 자동 회수(마지막 갱신 후 N일, 1시간 주기). `dns.record.expire-days`(기본 0=비활성).
- ⬜ 학번 네임스페이스: 미구현(이 문서로 미팅 후 결정 시 적용).
