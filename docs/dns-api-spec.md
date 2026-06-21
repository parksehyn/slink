# SOLID DNS 서비스 — API 명세 + 구현 현황

> 최종 업데이트: 2026-06-21
> 관련: [internal-dns-requirements.md](internal-dns-requirements.md), [relay-on-vm.md](relay-on-vm.md),
> 배포/실테스트 → [`../deploy/dns/README.md`](../deploy/dns/README.md)

SOLID DNS 서비스는 학생이 자신의 VM을 골라 `*.solid.internal` 내부 도메인을 연결하는 기능이다.
인증·VM 조회는 **실제 SOLID(CloudStack)**, DNS 레코드 저장·소유권 검증·CoreDNS 반영은 **DNS Server VM의
자족형 백엔드**(기존 Spring 앱, DNS 기능)가 담당한다. **터널링 Relay와는 분리**된다.

## 아키텍처

```
[브라우저] DNS 탭  ──SOLID 로그인──▶  [DNS Server VM = Spring 앱(DNS)]
                                       ├─ 레코드 저장(파일 영속, dns.store.file)
                                       ├─ vmId 소유권·사설IP 검증(CloudStack)
                                       └─ zone 파일 직접 작성(dns.zone.file) → CoreDNS reload
```

## 인증 (SOLID 세션 토큰)

| 경로 | 용도 |
|------|------|
| `POST /api/auth/login` | `{username, password, domain?}` → CloudStack `login` → `{token, account, domain, email}` |
| `GET /api/auth/me` | `Authorization: Bearer <token>` → `{account, domain, email}` |
| `POST /api/auth/logout` | 토큰 폐기 (204) |

- 토큰은 불투명 `slk-…`, 서버가 CloudStack sessionkey를 보관하고 VM 조회에 재사용. TTL 12h.
- 소유자 식별자 `ownerId = account`(학번). `cloudstack.api.url` 미설정 시 Mock(임의 자격·샘플 VM).

## VM 조회

| 경로 | 용도 |
|------|------|
| `GET /api/vms` | 내 VM 목록 `[{instanceId, displayName, privateIp, state, account}]` (CloudStack) |

## DNS 레코드 API (`/api/dns/records`)

모두 `Authorization: Bearer <token>` 필요. 에러는 표준 엔벨로프 `{success:false, error:{code,message}}`.

| 메서드·경로 | 설명 |
|------|------|
| `GET /api/dns/records` | 내 레코드 목록 |
| `GET /api/dns/records/{id}` | 단건 조회 |
| `POST /api/dns/records` | 생성 (A는 `vmId`, CNAME은 `value`) |
| `PATCH /api/dns/records/{id}` | `name`/`value`/`ttl`/`type` 수정 |
| `DELETE /api/dns/records/{id}` | 삭제 (204) |

### 생성 요청

```jsonc
// A: 서버가 vmId로 CloudStack에서 사설 IP 조회·소유권 검증 후 채움 (권장, §5.2/§7.3)
{ "type": "A", "name": "web", "vmId": "solid-32211690", "ttl": 3600 }
// CNAME: 대상 호스트
{ "type": "CNAME", "name": "alias", "value": "web.solid.internal", "ttl": 3600 }
```

### 응답

```jsonc
{
  "id": "uuid", "ownerId": "32211690", "type": "A",
  "name": "web", "fqdn": "web.solid.internal",
  "value": "10.0.11.24", "ttl": 3600,
  "vmId": "solid-32211690", "vmName": "demo-web",
  "status": "ACTIVE",
  "createdAt": "...", "updatedAt": "..."
}
```

- `name`은 짧은 라벨(루트는 `@`), `fqdn = name + ".solid.internal"`(서버 계산).
- 입력 이름은 트레일링 `.solid.internal`을 자동 제거·정규화.

## 검증 규칙

- **DNS 이름**: 소문자/숫자/하이픈(라벨), 루트 `@` 허용. 위반 → `INVALID_DNS_NAME`.
- **vmId(A)**: 필수. 없는/타인 VM → `VM_NOT_FOUND`/`VM_NOT_OWNED`. 미지정 → `INVALID_REQUEST`.
- **사설 IP(A)**: `10.0.0.0/8`만 허용. 루프백·169.254·0.0.0.0·공인 → `INVALID_IP_RANGE` (§7.2).
- **중복**: 전역 동일 이름 → `DUPLICATE_RECORD` (전역 유일, 선착순). 정책·향후안: [dns-naming-policy.md](dns-naming-policy.md).
- **TTL**: 30~86400초로 클램프(기본 3600).

## 상태값 (§8)

`PENDING_SYNC`(저장됨, 반영 대기) → `ACTIVE`(zone 반영 성공) / `FAILED`(반영 실패) / `DELETED`.
※ `DnsProvider` 호출 성공 시 `ACTIVE`. Mock 구현은 즉시 성공으로 처리.

## 에러 코드 (§10)

`UNAUTHORIZED`(401) · `NOT_FOUND`(404) · `INVALID_REQUEST`(400) · `INVALID_DNS_NAME`(400) ·
`INVALID_IP_RANGE`(400) · `DUPLICATE_RECORD`(409) · `VM_NOT_FOUND`(404) · `VM_NOT_OWNED`(403) ·
`DNS_SYNC_FAILED`(500).

## 설정 (application.properties)

| 키 | 효과 (미설정 시) |
|----|------|
| `dns.store.file` | 레코드 JSON 파일 영속 (인메모리) |
| `dns.zone.file` (+`dns.zone.name`, `dns.zone.ns-ip`) | CoreDNS zone 직접 작성 (Mock=로그만) |
| `cloudstack.api.url` | 실제 CloudStack 로그인·VM 조회 (Mock=임의 자격·샘플 VM) |

## 구현 현황

- ✅ SOLID 세션 인증(`AuthService`/`AuthController`), DNS·VM 엔드포인트 적용
- ✅ vmId 기반 생성 + 소유권·사설IP 검증, 상태값, fqdn/vmId/vmName/status 응답, 표준 에러, 단건 조회
- ✅ 파일 영속(`DnsRecordRegistry`), CoreDNS zone 직접 작성(`ZoneFileDnsProvider`)
- ✅ 실제 CloudStack provider 골격(`SolidCloudStackProvider`, 자격증명 설정 시 활성)
- ✅ 프론트 DNS 탭(SOLID 로그인, VM 선택→vmId 전송, 상태·fqdn 표시)

## 이번 범위 아님 (후속)

- 터널링 기능 / Colab CLI·VM Agent·SessionController의 `sk-dku-` 제거 (별도 트랙)
- 실제 CloudStack 자격증명·존 관리 권한 발급(운영팀 — internal-dns-requirements.md A-3)
- PUT 별칭, 짧은라벨 전용 검증, 소프트 삭제(DELETED 영속)
