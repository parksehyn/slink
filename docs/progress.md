# Solid-Link (slink) — 진행 현황 문서

> 최종 업데이트: 2026-05-24

## 프로젝트 목표

SOLID Cloud VM(code-server 환경) ↔ Google Colab T4 GPU 연결을 **학생 입력 최소화**로 구현.

```
목표: Colab 1줄 + VM 1줄 → GPU 사용 가능
```

---

## 시스템 아키텍처

### v1 — ngrok 방식 (데모 검증 완료)

```
[Google Colab]
  - JupyterLab 포트 8899 실행
  - pyngrok으로 HTTP 터널 → https://xxxx.ngrok-free.app
  - Relay 서버에 세션 등록
        ↓ POST /api/session/register
[Railway — Spring Boot Relay Server]
  URL: https://slink-production-3e7d.up.railway.app
        ↓ GET /api/session/by-owner/{email}
[로컬 / SOLID VM]
  $ slink connect
  → .vscode/settings.json 자동 갱신
  → VS Code에서 Colab GPU 커널 사용
```

**한계**: ngrok 무료 계정도 개인 authtoken 필수 (2024년부터 정책 변경)
→ 학생마다 ngrok 계정 생성 + Colab Secret에 `NGROK_AUTHTOKEN` 등록 필요

---

### v2 — Cloudflare Tunnel 방식 (구현 완료)

```
[Google Colab]
  - JupyterLab 포트 8899 실행
  - cloudflared Quick Tunnel → https://random.trycloudflare.com (계정/토큰 불필요)
  - Relay 서버에 세션 등록
        ↓ POST /api/session/register
[Railway — Spring Boot Relay Server]
  URL: https://slink-production-3e7d.up.railway.app
        ↓ GET /api/session/by-owner/{email}
[SOLID VM — code-server]
  $ slink connect
  → .vscode/settings.json 자동 갱신
  → VS Code에서 Colab GPU 커널 사용
```

**개선**: 학생 셋업에서 ngrok 계정/토큰 단계 완전 제거

---

## 구현 완료 항목

### ① 사용자 인증 시스템
- `POST /api/users/register` — 학번/이메일 → API Key 발급 (BCrypt 해시 저장)
- `GET /api/users/me` — Bearer 토큰으로 사용자 조회
- API Key 형식: `sk-dku-{hex16}`

### ② 세션 소유자 연동
- Session 모델에 `owner` 필드 추가
- `GET /api/session/by-owner/{email}` — Bearer 인증으로 본인 세션 조회
- `DELETE /api/session/by-owner/{email}` — 세션 정리
- 같은 소유자가 재등록 시 기존 세션 자동 교체

### ③ `slink init` CLI
- 학번 + 이메일 입력 → Relay에 등록 → API Key 발급
- `~/.slinkrc` (JSON)에 저장
- Colab Secret에 등록할 API Key 안내 출력

### ④ `slink connect` CLI
- `~/.slinkrc`에서 사용자 정보 자동 로드 (코드 입력 불필요)
- `.vscode/settings.json` 자동 갱신 (`jupyter.existingJupyterServer.uri`)
- keepalive 데몬 백그라운드 실행 (10분마다 ping → Colab 90분 끊김 방지)
- 세션 만료까지 남은 시간 표시

### ⑤ `GET /agent` 엔드포인트
- Relay 서버가 Python 부트스트랩 스크립트를 동적으로 반환
- `{{RELAY_URL}}`을 요청 헤더(X-Forwarded-Host)에서 자동 주입
- 원라이너 방식 지원: `exec(urllib.request.urlopen(...).read())`

### ⑥ slink-agent 패키지
- `agents/slink-agent/` — pip 설치 가능한 Python 패키지
- `pyproject.toml` 기반, `pip install git+https://github.com/parksehyn/slink.git#subdirectory=agents/slink-agent`
- Colab Secret에서 `SLINK_API_KEY` 자동 로드 (`google.colab.userdata`)

---

## 데모 실행 방법 (Cloudflare 방식)

> 미팅에서 직접 시연할 때 순서대로 따라가면 됨.

### 사전 준비 (1회)

```
[SOLID VM]
$ pipx install slink-cli
$ pipx ensurepath && source ~/.bashrc
$ slink init
  학번: 32211690
  이메일: hyun@dankook.ac.kr
  → API Key: sk-dku-xxxx 발급

[Colab Secrets 등록]
- 좌측 🔑 아이콘 → 새 보안 비밀 추가
  이름: SLINK_API_KEY / 값: sk-dku-xxxx
  "노트북 액세스 허용" ON
  (ngrok 계정/토큰 불필요)
```

### 매 데모 실행 순서

**Step 1 — Colab 빈 노트북 열기 → T4 GPU 선택 → 셀에 1줄 붙여넣고 실행**

```python
import urllib.request; exec(urllib.request.urlopen('https://slink-production-3e7d.up.railway.app/agent').read())
```

약 1분 후 출력:
```
[slink-agent] 사용자: hyun@dankook.ac.kr
[slink-agent] JupyterLab 시작 중...
[slink-agent] Cloudflare 터널 시작 중...
====================================================
  ✓ Colab GPU 준비 완료!
  연결 코드 : ABC123
  Jupyter  : https://xxxx.trycloudflare.com
  VM에서   : slink connect
====================================================
```

**Step 2 — SOLID VM에서 연결**

```bash
$ slink connect
```

출력:
```
==============================================================
  [slink] ✓ Connected  (세션 만료까지 11h 59m)

  URL  : https://xxxx.trycloudflare.com
  Token: 1aa155729380a15cb8a0eff00d52d01c
==============================================================
```

**Step 3 — VS Code에서 커널 연결**

```
1. .ipynb 파일 열기
2. 우측 상단 커널 선택 → Select Another Kernel → Existing Jupyter Server
3. URL 입력: https://xxxx.trycloudflare.com/?token=1aa155729380a15cb8a0eff00d52d01c
4. 서버 이름 입력 (ex: colab-gpu) → Python 3 커널 선택
```

**Step 4 — GPU 확인**

```python
import torch
print(torch.cuda.get_device_name(0))  # → Tesla T4
```

---

## 배포 현황

| 구성 요소 | 상태 | 위치 |
|-----------|------|------|
| Relay 서버 | ✅ 운영 중 | Railway (자동 배포, main 브랜치 push 시) |
| slink CLI | ✅ PyPI 배포 완료 | `pipx install slink-cli` / `agents/slink/slink.py` |
| slink-agent 패키지 | ✅ 완성 | `agents/slink-agent/` |
| Colab 노트북 | ✅ 동작 확인 | `agents/colab_agent.ipynb` |

---

## 발견된 문제 및 해결 방향

### 문제 1: ngrok 토큰 필수화 (해결 완료)
- **현상**: ngrok 무료 계정도 2024년부터 authtoken 필수 (ERR_NGROK_4018)
- **영향**: 학생마다 ngrok 계정 생성 + 토큰 발급 + Colab Secret 추가 필요
- **해결**: **Cloudflare Quick Tunnel로 교체 완료** (계정/토큰 불필요, `trycloudflare.com`)

### 문제 2: Google.colab VS Code extension 호환 불가
- **현상**: `Can't install 'google.colab': not compatible with code-server 1.95.2`
- **의미**: SOLID VM은 code-server 환경 → Google 공식 방법 원천 차단
- **결론**: slink 방식(Jupyter remote server)이 SOLID VM에서 유일한 해법임이 확인됨

### 문제 3: Colab Secret 서브프로세스 접근 불가 (해결됨)
- **현상**: `curl | python -` 실행 시 `google.colab.userdata` 접근 불가
- **원인**: Colab Secret은 메인 커널 프로세스에서만 접근 가능. `curl | python -`은 별도 Python 인터프리터를 fork하므로 접근 차단됨
- **해결**: `exec()` 방식 또는 env var 선주입 후 실행

**프로세스 구조 상세:**

```
[Colab 메인 커널 프로세스]
    ├── exec(코드)          → 같은 프로세스 안에서 실행
    │                         → userdata.get() 접근 가능 ✅
    │                         → os.environ 공유 ✅
    │
    └── curl | python -     → 새 Python 인터프리터 fork
                              → userdata.get() 차단 ❌
                              → Colab Secret은 별도 보안 채널이라 상속 안 됨
```

**`exec()` 방식의 한계:**
에이전트 코드 내부에서 `subprocess.Popen()`으로 JupyterLab, ngrok, cloudflared를 띄우는 건 결국 자식 프로세스임.
그래서 `exec()` 진입 직후 `_load_secret()`이 `userdata.get()`으로 시크릿을 읽어 `os.environ`에 세팅해야,
이후 자식 프로세스들이 `os.environ.get()`으로 값을 가져올 수 있음.

```python
# _load_secret() 내부 동작
def _load_secret(key):
    try:
        from google.colab import userdata
        return userdata.get(key) or ""   # exec()면 여기서 성공
    except Exception:
        return os.environ.get(key, "")   # env var 선주입 시 여기서 성공
```

---

## 트러블슈팅 기록

| 문제 | 원인 | 해결 |
|------|------|------|
| Jackson 3.x 직렬화 오류 | `write-dates-as-timestamps` 프로퍼티가 Jackson 3.x에 없음 | 프로퍼티 제거, `expiresAt`을 `Instant` → `String` 변환 |
| springdoc 404 | springdoc 2.x가 Spring Boot 4.0과 호환 안 됨 | springdoc 의존성 제거 |
| Railway URL 오작동 | `slink-production.up.railway.app`이 다른 앱을 가리킴 | 신규 도메인 `slink-production-3e7d.up.railway.app` 생성 |
| Gradle 빌드 실패 | Gradle 9.x에서 IBM_SEMERU 필드 제거됨 | Gradle 8.14 고정 |
| ngrok ERR_NGROK_4018 | ngrok 무료 계정도 authtoken 필수 | Cloudflare Tunnel 전환 완료 |

---

## 환경별 테스트 고려사항

### 로컬 환경 (Windows + 네이티브 VS Code)

| 항목 | 상태 | 비고 |
|------|------|------|
| `slink connect` 실행 | ✅ 확인 | PowerShell에서 정상 동작 |
| `.vscode/settings.json` 갱신 | ✅ 확인 | 실행 경로 기준으로 생성 |
| Jupyter extension | ✅ Microsoft 마켓플레이스에서 설치 가능 | |
| 커널 연결 | ✅ 확인 | URL+Token 방식 |
| `ms-toolsai.jupyter` 호환 | ✅ 네이티브 VS Code는 제한 없음 | |
| Google.colab extension | ✅ 설치 가능 (단, 사용 안 함) | |

**로컬 특이사항**
- `slink connect`를 실행하는 폴더가 VS Code에서 열린 워크스페이스와 일치해야 settings.json이 반영됨
- Windows 경로 (`\`) → Jupyter URL은 `/`로 처리되므로 무관

---

### SOLID VM 환경 (Linux + code-server)

| 항목 | 상태 | 비고 |
|------|------|------|
| `slink connect` 실행 | ✅ 동작 가능 | Linux CLI 동일 |
| `.vscode/settings.json` 갱신 | ✅ 동작 가능 | 워크스페이스 폴더 기준 |
| Jupyter extension | ⚠️ **확인 필요** | Open VSX에서 `ms-toolsai.jupyter` 설치해야 함 |
| 커널 연결 | ✅ 동작 가능 | 서버 측에서 터널 URL에 접속하는 구조 |
| Google.colab extension | ❌ 호환 불가 | code-server 1.95.2와 버전 미지원 |
| Microsoft 마켓플레이스 | ❌ 접근 불가 | code-server는 Open VSX 사용 |

**SOLID VM 특이사항**
- `ms-toolsai.jupyter`가 Open VSX에 등록되어 있어 설치 가능 — 단, SOLID VM 관리자가 외부 extension 설치를 허용하는지 확인 필요
- code-server 창 새로고침(F1 → Reload Window) 후 settings.json이 반영되는 경우 있음
- `slink connect`는 SSH 또는 SOLID VM 내 터미널에서 실행해야 올바른 경로에 settings.json 생성됨
- Google 공식 Colab extension이 동작하지 않으므로 **slink 방식이 유일한 GPU 연결 방법**

---

### 두 환경 공통 전제조건

```
1. Colab에서 slink-agent 실행 완료 (JupyterLab + 터널 + 세션 등록)
2. slink init 완료 (~/.slinkrc 존재)
3. ms-toolsai.jupyter extension 설치됨
4. slink connect를 VS Code 워크스페이스 폴더 내에서 실행
```

---

## 실제 동작 확인 (2026-05-24) — Cloudflare 방식 기준

end-to-end 테스트 통과 (SOLID VM 환경):

```
[Colab]  원라이너 실행
         import urllib.request; exec(urllib.request.urlopen('https://slink-production-3e7d.up.railway.app/agent').read())
           → cloudflared 설치
           → JupyterLab 기동 (port 8899)
           → Cloudflare Quick Tunnel 생성 (trycloudflare.com)
           → Relay에 세션 등록
         출력: ✓ Colab GPU 준비 완료!
               Jupyter: https://xxxx.trycloudflare.com

[SOLID VM]
         $ slink connect
         출력: ✓ Connected (세션 만료까지 11h 59m)

[VS Code / ipynb]
         import torch
         print(torch.cuda.get_device_name(0))  → 'Tesla T4' ✓
```

---

## 트러블슈팅 기록 (2026-05-24 추가)

| 문제 | 원인 | 해결 |
|------|------|------|
| `pip install` → `externally-managed-environment` 오류 | SOLID VM이 Debian 계열, 시스템 Python 보호 | `pipx install` 사용 |
| `pipx install` → Authentication failed (exit code 128) | GitHub 레포가 private | 레포를 public으로 변경 |
| `pipx install` 성공 후 `slink` 명령어 not found | `/home/ubuntu/.local/bin`이 PATH에 없음 | `pipx ensurepath && source ~/.bashrc` |
| `pip install` GitHub 클론 매우 느림 | SOLID VM 네트워크 속도 | `pipx` 사용 또는 `curl`로 단일 파일 다운로드 |

---

## 2026-05-24 작업 내역

### 완료한 작업

| 작업 | 내용 |
|------|------|
| Cloudflare 전환 완료 | `agent.py` ngrok → Cloudflare 교체, `agent_cf.py` 삭제, `/agent-cf` 엔드포인트 제거 |
| `colab_agent.ipynb` 교체 | 구버전 ngrok 노트북 → 원라이너 1줄짜리로 교체 |
| PyPI 배포 | `pipx install slink-cli` 로 설치 가능 (v0.1.0) |
| 백그라운드 실행 | `slink connect -d` (터미널 점유 없음), `slink disconnect`, `slink status` 추가 (v0.2.0) |
| end-to-end 테스트 | SOLID VM에서 Cloudflare 방식으로 Tesla T4 연결 확인 |

### 조사한 내용: Relay 서버를 SOLID VM에 올릴 수 있는가?

**결론: 불가능 — Railway 유지 필요**

SOLID Cloud VM은 OpenVPN을 통해서만 접근 가능한 사설 네트워크(`10.0.X.X`)입니다.
Relay 서버는 Colab(Google 서버)에서 HTTP 요청을 받아야 하는데,
Google 서버가 VPN 없이 `10.0.X.X`에 접근하는 방법이 없습니다.

```
Colab (Google 서버) → Relay 서버   ← VPN 없이 불가능
SOLID VM           → Relay 서버   ← VPN으로 가능
```

→ Relay 서버는 공인 IP를 가진 외부 서비스(현재 Railway)에 있어야 함.
→ 교수님 미팅 시 Railway 또는 학교 공개 서버 자원 확보 논의 필요.

---

## Service Portal (구현 중)

로드맵 1~3단계 구현 완료:

- [x] `Service` 모델 및 저장소 (`ServiceEntry`, 인메모리)
- [x] 서비스 CRUD API (`/api/services/*`)
- [x] 외부 공개 API (`POST/DELETE /api/services/{id}/publish`)
- [x] `TunnelProvider` 인터페이스에서 `privateIp` 제거 — 터널 대상은 VM Agent loopback으로 제한
- [x] publish/unpublish 시 scope 상태 일관성 보장 (pre-publish scope 복원)
- [x] PUBLIC scope 직접 선택 차단 (create/update에서 400 반환)
- [x] 이름 변경 시 `internalHostname`·DNS 레코드 갱신
- [x] DNS·터널 인터페이스 분리 (`DnsProvider`, `TunnelProvider`) + 모의 구현
- [x] Service Portal 웹 UI (`/portal/index.html`)
- [x] 기존 API 회귀 테스트 + Service API 통합 테스트
- [x] VM Agent 등록 및 heartbeat (`/api/agents/register`, `/heartbeat`)
- [x] VM Agent 기반 Cloudflare Quick Tunnel 생성·종료
- [x] SOLID VM 실제 왕복 테스트 완료 (`localhost:3000` → `trycloudflare.com`)

미완료:

- [ ] 실제 내부 DNS 연동 (SOLID 운영 환경 DNS 설정 권한 확인 후 진행)
- [ ] CloudStack API 연동 (VM 소유권 검증) — 완료 전까지 실제 TunnelProvider 비활성
- [ ] 영속 저장소 도입 (서버 재시작 후 서비스 목록 유지)
- [ ] 학생별 공개 서비스 개수 및 TTL 정책 적용

## 다음 단계

- [ ] VM 간 임의 포트 접근 가능 여부 확인
- [ ] 내부 DNS 설정 권한 및 운영 정책 확인
- [ ] Relay 서버 장기 운영 방안 논의 (Railway 유지 vs 학교 공개 서버)

---

## 사용자 흐름 (현재 기준 — Cloudflare 방식)

### 학기 초 1회 셋업

```
[SOLID VM]
$ pipx install slink-cli
$ pipx ensurepath && source ~/.bashrc
$ slink init
  학번: 32211690
  이메일: hyun@dankook.ac.kr
  → API Key: sk-dku-xxxx 발급

[Colab] 좌측 🔑 → 새 보안 비밀 추가
  이름: SLINK_API_KEY / 값: sk-dku-xxxx
  노트북 액세스: ON
  (ngrok 계정/토큰 불필요)
```

### 매일 사용

```
1. Colab 빈 노트북 열기 → T4 GPU 선택
2. 셀에 1줄 붙여넣고 실행 (약 1분 대기):
   import urllib.request; exec(urllib.request.urlopen('https://slink-production-3e7d.up.railway.app/agent').read())
3. SOLID VM에서: $ slink connect
4. VS Code에서 .ipynb 열고 커널 연결
   → torch.cuda.get_device_name(0) 으로 GPU 확인
```

---

## 사용자 관점 경우의 수

### 정상 흐름

| 상황 | 해야 할 것 |
|------|-----------|
| 처음 사용 (학기 초) | `slink init` → Colab Secret 등록 → 원라이너 → `slink connect` |
| 매일 새로 시작 | 원라이너 → `slink connect` |
| `slink connect` Ctrl+C 후 재연결 | `slink connect` 만 |
| `slink connect` 끊겼는데 Colab은 살아있음 | `slink connect` 만 |

### Colab 관련

| 상황 | 해야 할 것 |
|------|-----------|
| Colab 탭 닫았다가 다시 열었는데 런타임 살아있음 | `slink connect` 만 |
| Colab 탭 닫았다가 다시 열었는데 런타임 죽어있음 | 원라이너 → `slink connect` |
| Colab 런타임 수동 종료 후 재시작 | 원라이너 → `slink connect` |
| Colab 90분 무활동으로 끊김 | 원라이너 → `slink connect` |
| Colab 12시간 최대 세션 만료 | 원라이너 → `slink connect` |

### 에러 상황

| 에러 메시지 | 원인 | 해결 |
|------------|------|------|
| `등록된 세션이 없습니다` | Colab이 꺼져있음 | 원라이너 먼저 실행 |
| `인증 실패. API Key를 확인하세요` | `~/.slinkrc` 손상 또는 없음 | `slink init` 재실행 |
| `Relay 서버에 연결할 수 없습니다` | Railway 서버 다운 | 잠시 후 재시도 |
| VS Code에서 커널 연결 안 됨 | Colab 꺼진 후 `slink connect`만 한 경우 | 원라이너 → `slink connect` |
| `SLINK_API_KEY를 등록하세요` | Colab Secret 미등록 | Colab 🔑 에서 등록 |

> **핵심 규칙**: Colab 런타임이 새로 시작됐으면 → 원라이너 실행. 그 외에는 → `slink connect` 만.
