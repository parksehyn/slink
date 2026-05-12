# Solid-Link (slink) — 프로젝트 가이드

SOLID Cloud VM ↔ Google Colab GPU 연동 시스템.

## 아키텍처

```
[Colab] colab_agent.ipynb
  - JupyterLab 포트 8899 실행
  - Cloudflare Quick Tunnel로 외부 노출 (trycloudflare.com, 토큰 불필요)
  - Railway Relay 서버에 세션 등록 → 6자리 코드 발급

[Railway] Spring Boot Relay Server
  URL: https://slink-production-3e7d.up.railway.app
  GitHub: https://github.com/parksehyn/slink

[VS Code / SOLID VM] slink.py
  - python slink.py connect {코드} --relay https://slink-production.up.railway.app
  - Jupyter URL + Token 출력 → VS Code에서 연결
```

## 파일 구조

```
connectGPU/
├── src/main/java/com/solid/connectgpu/
│   ├── controller/SessionController.java   # POST/GET/DELETE /api/session
│   ├── service/SessionService.java         # 코드 생성, TTL 12시간
│   ├── model/Session.java
│   └── dto/                               # RegisterRequest, SessionResponse
├── agents/
│   ├── colab_agent.ipynb                  # Colab에서 실행
│   └── slink/
│       ├── slink.py                       # CLI 툴
│       └── requirements.txt              # requests
├── docs/
│   └── design-a.md                        # 학생 사용 설계(A안) — 데모/디벨롭 로드맵
├── Procfile                               # Railway 시작 명령
└── build.gradle                           # Spring Boot 4.0, Gradle 8.14, Java 17
```

## 주요 설정

- **Gradle**: 8.14 (9.x는 IBM_SEMERU 오류 발생)
- **포트**: `${PORT:8081}` (Railway는 PORT 환경변수로 동적 할당)
- **세션 TTL**: 12시간, 5분마다 만료 정리

## API

| Method | Path | 역할 |
|--------|------|------|
| POST | /api/session/register | Colab 에이전트가 세션 등록 |
| GET | /api/session/{code} | slink CLI가 세션 조회 |
| DELETE | /api/session/{code} | Colab 종료 시 정리 |

## 사용 방법

1. `agents/colab_agent.ipynb` → Colab에 업로드 → T4 GPU 선택 → 모두 실행
2. Cell 4에서 `RELAY_SERVER_URL = "https://slink-production.up.railway.app"` 입력
3. 연결 코드 + URL + Token 확인
4. VS Code → 커널 선택 → 기존 Jupyter 서버 → URL + Token 입력

## 터널 방식: Cloudflare Quick Tunnel

ngrok 대신 Cloudflare Tunnel(`cloudflared`)을 사용한다. 이유:
- 계정/토큰 없이 즉시 `https://random.trycloudflare.com` URL 발급
- 학생마다 ngrok 계정을 만들 필요 없음 (ngrok free tier는 토큰 필수)

에이전트에서 사용하는 방식:
```python
proc = subprocess.Popen(
    ["cloudflared", "tunnel", "--url", "http://localhost:8899"],
    stderr=subprocess.PIPE, stdout=subprocess.DEVNULL,
)
for line in proc.stderr:
    m = re.search(r'https://[a-z0-9-]+\.trycloudflare\.com', line.decode())
    if m:
        tunnel_url = m.group(0)
        break
```

## 주의사항

- Cloudflare Quick Tunnel 사용 (계정·토큰 불필요, `trycloudflare.com`)
- Colab 재실행 전 `pkill -9 -f cloudflared` 로 이전 프로세스 정리
- Railway 배포 후 로컬 터널 불필요

## 관련 문서

- [`docs/design-a.md`](docs/design-a.md) — 학생 사용 설계(A안): Colab Agent 배포 방식 비교(PyPI vs 원라이너), `slink init`/`connect` 흐름, Relay API 변경사항, 디벨롭 우선순위.
