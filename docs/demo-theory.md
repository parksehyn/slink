# slink 데모 이론 — 역방향 터널링 (외부 공개)

> 작성일: 2026-06-12
> 관련 문서: [demo-runbook.md](demo-runbook.md) (실행 순서), [service-portal-design.md](service-portal-design.md), [roadmap.md](roadmap.md)

미팅에서 "외부 공개(PUBLIC) 데모가 어떻게 동작하는가"를 설명하기 위한 이론 정리.

## 1. 한 줄 요약

> 학생은 포털에서 **'공개'만 누르고**, Relay가 VM 안 Agent에게 신호를 보내
> **Agent가 직접 바깥으로 터널을 연다.** Relay는 인증·신호 중개만 하고,
> 실제 트래픽은 Cloudflare를 통해 흐른다. 그래서 사설망 VM도 공인 IP 없이 외부 공개가 된다.

## 2. 핵심 개념 — 컨트롤 플레인 vs 데이터 플레인

이 시스템 전체는 이 한 줄로 요약된다.

> **Relay는 "신호"만 중개하고, 실제 "트래픽"은 Relay를 거치지 않는다.**

| 구분 | 무엇이 흐르나 | 경로 |
|------|--------------|------|
| **컨트롤 플레인** | 인증·명령·주소(메타데이터) | 포털 ↔ **Relay** ↔ Agent |
| **데이터 플레인** | 실제 HTTP 요청·응답 | 외부 사용자 → Cloudflare → cloudflared → VM:포트 |

Relay는 컨트롤 플레인에만 있고, 데이터 플레인에는 없다.

## 3. 등장 요소

```text
[학생 브라우저]   포털 UI
[Relay/Registry] 공인 서버(Railway) — 인증 + 서비스 레지스트리 + 명령 큐
[VM Agent]       VM 안에서 도는 프로세스 — Relay 폴링 + cloudflared 실행
[cloudflared]    VM에서 바깥으로 나가는 터널
[Cloudflare Edge] 공개 URL(trycloudflare.com) 제공
[VM 로컬 서비스]  localhost:포트 (예: node 앱 8080)
```

## 4. 시퀀스 — 공개가 일어나는 순서

```text
① 인증
   브라우저 ──API Key──▶ Relay
   "이 키 = hyun@dankook" 확인

② 서비스 등록
   포털 "Add Service" ──▶ Relay 레지스트리에 기록
   { owner: hyun, instance: solid-32211690, port: 8080, scope: INTERNAL }

③ Agent 상시 대기
   VM Agent ──10초마다 heartbeat──▶ Relay
   "나 solid-32211690의 Agent, 명령 있어?"

④ 공개 요청
   포털 "외부 공개 시작" ──▶ Relay
   Relay: 서비스 상태 PENDING + OPEN_TUNNEL 명령을 큐에 적재

⑤ 명령 수신 (랑데부)
   다음 heartbeat에서 Agent가 OPEN_TUNNEL 받아감
   ※ owner + instanceId 일치하는 명령만 전달 (격리)

⑥ 터널 생성
   Agent ──▶ cloudflared tunnel --url localhost:8080 실행
   cloudflared ──outbound──▶ Cloudflare Edge
   → https://xxxx.trycloudflare.com 발급

⑦ 주소 보고
   Agent ──TUNNEL_READY + URL──▶ Relay
   Relay: 상태 PENDING → PUBLIC, publicUrl 저장
   포털 자동 갱신(10초) → 공개 URL 표시

⑧ 실제 접속 (여기엔 Relay 없음)
   외부 사용자 → trycloudflare.com → Cloudflare → cloudflared → VM:8080
```

상태 전이: 공개 클릭 직후 `PENDING`(명령 적재, 미수신) → Agent가 URL 보고(`TUNNEL_READY`)하면 `PUBLIC`.
공개 누르고 ~10초간 "대기 중(PENDING)"이 보이는 것이 정상 동작이다.

## 5. 설계 근거 (왜 이 구조인가)

### 5.1 왜 "역방향(outbound) 터널"인가

SOLID VM은 사설 IP(`10.0.X.X`) → 외부에서 **들어올(inbound) 수 없음.**
그래서 VM이 **바깥으로 나가서(outbound)** 터널을 연다. 공인 IP·외부 포트 개방 불필요.

### 5.2 왜 Relay가 공인 서버(Railway)여야 하나

Agent·포털·CLI 모두가 닿아야 하는 **랑데부 지점.**
VM은 사설망이라 서로의 localhost에는 못 닿고, 공인 인터넷(Railway)으로만 만날 수 있다.
→ Relay는 SOLID VM 안에 둘 수 없다. (Colab도 같은 이유로 Railway에 등록한다.)

### 5.3 왜 Relay는 트래픽을 안 나르나 (중요)

Relay가 데이터까지 중계하면 모든 학생 트래픽이 한 곳에 몰려 병목·과금 폭증.
실제 트래픽은 Cloudflare 글로벌 망이 처리하고, Relay는 **"누가·어디로 터널 열어라"** 신호만 다룬다.
→ Relay는 가볍고 확장 가능.

## 6. "왜 데모를 Railway URL로 하나" — 자주 나오는 질문

포털은 정적 HTML이며 **자기를 내려준 서버로 API 요청을 보낸다**(상대경로 `/api/...`).

- Railway에서 열면 → API가 Railway로 감 → Agent가 등록된 그 레지스트리 ✅
- localhost에서 열면 → API가 로컬 서버로 감 → Agent 없는 빈 레지스트리 ❌

명령 전달 구조:

```text
포털(공개) ─쓰기─▶ [레지스트리] ◀─폴링─ Agent
                  반드시 같은 인스턴스여야 함
```

결정권은 포털이 아니라 **Agent**가 쥔다. VM Agent는 공인 인터넷밖에 못 나가므로 **무조건 Railway에 등록·폴링** → 레지스트리=Railway → 포털도 Railway.

> "Railway를 쓴다"는 **구조상 필수**(VM이 닿을 곳이 거기뿐),
> "Railway에서 연 포털 페이지를 쓴다"는 **편의**(로컬 파일로 열어도 API는 Railway를 가리켜야 하므로 무의미).

## 7. 서비스 등록 필드의 실제 역할

| 필드 | 중요도 | 역할 |
|------|--------|------|
| `instanceId` | 결정적 | Agent의 `--instance-id`와 **정확히 일치**해야 명령이 그 Agent로 감 (라우팅 키) |
| `localPort` | 결정적 | Agent가 `cloudflared --url localhost:<포트>` 실행. **VM에서 실제 listen 중인 포트와 같아야** 함 |
| `privateIp` | 형식만 | 입력 필수(비면 400)지만 PUBLIC 터널엔 미사용. 터널은 VM **loopback**으로만 연결. 내부 DNS(INTERNAL)용 |
| `protocol` | — | HTTP / HTTPS |
| `scope` | — | INTERNAL/PRIVATE로 생성 (PUBLIC은 publish로만) |

흔한 실패: **포털 포트 ≠ VM 실제 listen 포트** → 터널은 열리지만 빈 포트를 가리켜 **502 Bad Gateway.**
→ "포털에 적은 포트 == VM에서 실제 listen 중인 포트"만 맞추면 해결.

## 8. 접근 범위(Scope)별 실제 동작 상태

| Scope | 접근 대상 | 실제 동작? |
|-------|----------|-----------|
| `PRIVATE` | 소유자 본인 | 등록만 (공유 주소 없음) |
| `INTERNAL` | SOLID 네트워크 사용자 | 🟡 이름만 표시, **실제 접속은 모의** (DNS 권한 확보 후 실연동) — [internal-dns-requirements.md](internal-dns-requirements.md) 참조 |
| `PUBLIC` | 인터넷 사용자 | ✅ **실제 동작** (cloudflared 터널) |

PUBLIC은 직접 선택 불가 — PRIVATE/INTERNAL로 생성 후 "외부 공개 시작"으로 전환된다.
</content>
</invoke>
