# Solid-Link (slink) — 진행 현황 문서

> 최종 업데이트: 2026-05-12

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

### v2 — Cloudflare Tunnel 방식 (적용 예정)

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

## 데모 실행 방법 (ngrok 방식)

> 미팅에서 직접 시연할 때 순서대로 따라가면 됨.

### 사전 준비 (1회)

```
[Colab Secrets 등록]
- 좌측 🔑 아이콘 → 새 보안 비밀 추가
  ① 이름: SLINK_API_KEY  / 값: sk-dku-xxxx  (slink init으로 발급받은 키)
  ② 이름: NGROK_AUTHTOKEN / 값: <ngrok 계정 토큰>
  → 두 항목 모두 "노트북 액세스 허용" ON

[로컬 / SOLID VM]
$ pip install requests
$ python agents/slink/slink.py init
  학번: 32211690
  이메일: hyun@dankook.ac.kr
```

### 매 데모 실행 순서

**Step 1 — Colab에서 에이전트 실행**

```python
# Colab 셀에 붙여넣고 실행
import os
from google.colab import userdata
os.environ['SLINK_API_KEY'] = userdata.get('SLINK_API_KEY')
os.environ['NGROK_AUTHTOKEN'] = userdata.get('NGROK_AUTHTOKEN')

exec(open('agent.py').read())
# 또는 원라이너:
# import urllib.request; exec(urllib.request.urlopen('https://slink-production-3e7d.up.railway.app/agent').read())
```

약 1분 후 출력:
```
[slink-agent] 사용자: hyun@dankook.ac.kr
[slink-agent] JupyterLab 시작 중...
[slink-agent] ngrok 터널 시작 중...
====================================================
  ✓ Colab GPU 준비 완료!
  연결 코드 : ABC123
  Jupyter  : https://xxxx.ngrok-free.app
  VM에서   : slink connect
====================================================
```

**Step 2 — VM / 로컬에서 연결**

```bash
$ python agents/slink/slink.py connect
```

출력:
```
[slink] hyun@dankook.ac.kr 세션 조회 중...
[slink] VS Code 설정 갱신: .vscode/settings.json
[slink] keepalive 데몬 시작 (10분마다 ping)

==============================================================
  [slink] ✓ Connected  (세션 만료까지 11h 59m)

  URL  : https://xxxx.ngrok-free.app
  Token: 1aa155729380a15cb8a0eff00d52d01c
==============================================================
```

**Step 3 — VS Code에서 커널 연결**

```
1. .ipynb 파일 열기
2. 우측 상단 커널 선택 → Select Another Kernel → Existing Jupyter Server
3. URL 입력: https://xxxx.ngrok-free.app/?token=1aa155729380a15cb8a0eff00d52d01c
4. 서버 이름 입력 (ex: colab-gpu) → Python 3 커널 선택
```

**Step 4 — GPU 확인**

```python
import torch
print(torch.cuda.get_device_name())  # → Tesla T4
```

---

## 실제 동작 확인 (2026-05-11) — ngrok 방식 기준

end-to-end 테스트 통과:

```
[Colab]  원라이너 실행
           → JupyterLab 기동 (port 8899)
           → ngrok HTTP 터널 생성
           → Relay에 세션 등록
         출력: ✓ Colab GPU 준비 완료!
               연결 코드: XXXXXX
               Jupyter  : https://unvented-decimal-endorphin.ngrok-free.dev

[SOLID VM / 로컬]
         $ slink connect
         출력: ✓ Connected (세션 만료까지 11h 59m)
               URL  : https://unvented-decimal-endorphin.ngrok-free.dev
               Token: 1aa155729380a15cb8a0eff00d52d01c

[VS Code] torch.cuda.get_device_name() → 'Tesla T4'  ✓
```

> 단, 테스트 시 Colab Secret에 `NGROK_AUTHTOKEN`을 별도 등록한 상태에서 진행함.
> 이 단계를 없애기 위해 v2(Cloudflare) 전환 예정.

---

## 배포 현황

| 구성 요소 | 상태 | 위치 |
|-----------|------|------|
| Relay 서버 | ✅ 운영 중 | Railway (자동 배포, main 브랜치 push 시) |
| slink CLI | ✅ 완성 | `agents/slink/slink.py` |
| slink-agent 패키지 | ✅ 완성 | `agents/slink-agent/` |
| Colab 노트북 | ✅ 동작 확인 | `agents/colab_agent.ipynb` |

---

## 발견된 문제 및 해결 방향

### 문제 1: ngrok 토큰 필수화 (해결 예정)
- **현상**: ngrok 무료 계정도 2024년부터 authtoken 필수 (ERR_NGROK_4018)
- **영향**: 학생마다 ngrok 계정 생성 + 토큰 발급 + Colab Secret 추가 필요
- **해결 방향**: **Cloudflare Quick Tunnel로 교체** (계정/토큰 불필요, `trycloudflare.com`)
- **상태**: CLAUDE.md에 설계 기록 완료, 코드 적용 예정

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
| ngrok ERR_NGROK_4018 | ngrok 무료 계정도 authtoken 필수 | Cloudflare Tunnel 전환 예정 |

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

## 다음 단계

- [ ] Cloudflare Quick Tunnel 적용 (`agent.py`, `slink_agent/agent.py`, `colab_agent.ipynb`)
- [ ] SOLID VM에서 `ms-toolsai.jupyter` 설치 가능 여부 확인
- [ ] SOLID VM에서 `slink connect` → `.vscode/settings.json` 반영 → 커널 연결 end-to-end 테스트
- [ ] 학생 배포용 Colab 노트북 최종 정리
- [ ] `slink reset` 명령 (API Key 재발급)

---

## 사용자 흐름 (현재 기준)

### 학기 초 1회 셋업

```
[SOLID VM]
$ pip install requests
$ python slink.py init
  학번: 32211690
  이메일: hyun@dankook.ac.kr
  → API Key: sk-dku-xxxx 발급

[Colab] 좌측 🔑 → 새 보안 비밀 추가
  이름: SLINK_API_KEY / 값: sk-dku-xxxx
  노트북 액세스: ON
```

### 매일 사용

```
1. Colab 북마크 클릭 → GPU 노트북 열기
2. Ctrl+F9 (모두 실행) → 약 1분 대기
3. SOLID VM에서: $ slink connect
4. VS Code에서 .ipynb 열고 커널 자동 연결
```