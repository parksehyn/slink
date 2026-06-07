# Solid-Link 학생 사용 설계 — A안 (Colab 기반)

> 이 문서는 Colab GPU 연결 설계(A안) 기록입니다. 구현은 완료된 상태이며, Service Portal 확장 설계는 [service-portal-design.md](service-portal-design.md)를 참조하세요.

> Colab을 GPU 백엔드로 유지하면서 학생 입력 마찰을 본질적 하한선까지 줄이는 설계.

## 설계 원칙

- **Colab의 "1회 노트북 열기"는 자동화 불가**: Google이 무료 GPU의 대가로 건 정책 제약. 콜드 부팅 API가 없고 활성 사용자 확인이 있어 헤드리스 자동화 차단됨.
- **그 외 모든 상호작용은 0에 가깝게**: 학기 초 1회 셋업 + 매일 사용 2~3단계로 분리.
- **무료 유지**: 학교/학생 추가 비용 0.

## 사용자 흐름

### 학기 초 1회 셋업

```
① VM에서:
   $ slink init
   ? 학번: 32211690
   ? 이메일: hyun@dankook.ac.kr
   [slink] ✓ 등록 완료
   [slink] API Key: sk-dku-1a2b3c4d5e
   [slink] 설정 저장: ~/.slinkrc

② Colab에서 (1회):
   - 좌측 🔑 아이콘 → "새 보안 비밀 추가"
   - 이름: SLINK_API_KEY / 값: 위 키 붙여넣기
   - 노트북 액세스 허용 ON

③ Colab 노트북을 Drive에 사본 저장 → 브라우저 북마크
```

### 매일 사용 (3단계)

```
1. Colab 북마크 클릭        → 탭 열림 (Google 자동 로그인)
2. Ctrl+F9                  → "모두 실행" (1분 대기)
3. VM에서 $ slink connect   → VS Code 셀 실행 가능
```

**클릭 2회 + 명령어 1줄**. Colab 기반 설계의 사실상 최저점.

## Colab Agent 배포 방식 — 후보 비교

학기 초 학생 Colab 노트북에 들어갈 "agent를 어떻게 받아오는가"의 두 가지 후보.

### 후보 1: PyPI 패키지 (`slink-agent`)

**학생 노트북 내용 (3줄):**
```python
!pip install slink-agent --quiet
import slink_agent
slink_agent.run()   # Colab Secrets에서 SLINK_API_KEY 자동 로드
```

**장점**
- 버전 관리 (`slink-agent==1.2.0` 명시 가능, semver)
- 패키지 매니저 표준 워크플로 — 누구나 익숙
- IDE/타입 힌트 지원
- 의존성 명시 (`pyproject.toml`의 `dependencies`)

**단점**
- PyPI 행정 비용: 이름 선점, twine 셋업, 첫 배포 ~2시간
- 업데이트 시 학생이 `pip install --upgrade`를 명시적으로 해야 함 (또는 캐시 무효화)
- 작은 패치 배포에도 새 버전 태그 필요

### 후보 2: 원라이너 (The One-Liner)

**학생 노트북 내용 (1줄):**
```python
exec(__import__('urllib.request').urlopen('https://slink-production.up.railway.app/agent').read())
```

또는 셸 형태:
```python
!curl -sSL https://slink-production.up.railway.app/agent | python -
```

**동작**: Relay 서버가 `/agent` 엔드포인트로 최신 Python 스크립트 반환 → Colab이 그대로 실행 → 내부에서 `pip install` + ngrok 시작 + Relay 등록까지 한 번에.

**장점**
- 학생 입력 **1줄로 끝** (3줄 → 1줄)
- 빈 Colab 노트북에 그대로 붙여넣기 가능 — ipynb 파일 배포 불필요
- 항상 최신 버전 자동 (서버 파일만 갱신하면 다음 실행부터 적용)
- PyPI 등록 절차 0 — Relay 서버에 파일 1개 추가하면 끝
- 학기 중 핫픽스 즉시 반영 (학생 개입 0)

**단점**
- **보안 표면적 큼**: 원격 코드를 매번 그대로 실행 → Relay 서버가 침해되면 모든 학생 Colab에서 임의 코드 실행(RCE). HTTPS + 가능하면 스크립트 해시 검증 필요.
- 버전 고정 어려움 (URL에 버전 박을 수는 있음: `/agent/v1.2`)
- "왜 이렇게 동작하는지" 디버깅 어려움 — 학생이 코드를 보려면 URL을 따로 열어야 함
- 강의 자료에 적기엔 약간 비표준적인 인상

### 후보 비교 표

| 항목 | PyPI 패키지 | 원라이너 |
|------|------------|----------|
| 학생 입력 | 3줄 | **1줄** |
| 노트북 ipynb 배포 | 불필요 | **불필요** |
| 빈 노트북에 즉시 사용 | OK | **OK** |
| 최신 버전 자동 | △ (`--upgrade` 필요) | **○** |
| 버전 고정 | **○** | △ (URL 분기 필요) |
| 보안 신뢰 모델 | PyPI 신뢰 | Relay 서버 신뢰 |
| 배포 절차 | PyPI 1회 셋업 + 태그 푸시 | Relay에 파일 1개 |
| 자소서/포트폴리오 어필 | 패키지 배포 역량 | 시스템 설계 역량 |

### 권장: 하이브리드

두 방식이 상호 배타적이지 않습니다.

```python
# 학생이 노트북에 붙여넣는 1줄
!curl -sSL https://slink-production.up.railway.app/agent | python -
```

내부 동작:
1. `/agent` 응답이 PyPI에서 `slink-agent` 최신 버전을 `pip install` → 실행 트리거.
2. 즉, **원라이너가 PyPI 패키지의 부트스트랩**이 됨.

이러면:
- 학생: 1줄 입력 (원라이너의 장점)
- 코드: PyPI에 깔끔히 버전 관리 (패키지의 장점)
- 핫픽스: PyPI에 새 버전 푸시 → 다음 실행부터 자동
- 보안: 원라이너 스크립트는 짧고 검증 가능 (`pip install slink-agent==X.Y.Z; ...`), 실제 로직은 PyPI에

데모 미팅 시점엔 **원라이너 단독** 또는 **PyPI 단독** 중 하나로 시작하고, 나머지는 학기 시작 전 합류시켜도 됩니다.

## `slink connect` 내부 동작

```
$ slink connect
  ① ~/.slinkrc에서 user + api_key 로드
  ② GET /api/session/by-owner/hyun@dankook.ac.kr  (Bearer sk-dku-...)
  ③ Relay 검증 → { ngrokHost, jupyterToken, expiresAt }
  ④ .vscode/settings.json 자동 갱신 (jupyter.existingJupyterServer)
  ⑤ keepalive 데몬 백그라운드 실행 (10분마다 ping → 90분 끊김 방지)
  ⑥ 출력: ✓ Connected (Tesla T4, 만료까지 11h 47m)
```

## Relay 측 변경사항

| API | 신규/변경 | 용도 |
|-----|----------|------|
| `POST /api/users/register` | 신규 | 학번/이메일 등록, API Key 발급 |
| `POST /api/session/register` | 변경 (`owner` 필드 추가) | Colab 등록 시 소유자 명시 |
| `GET /api/session/by-owner/{id}` | 신규 | 소유자별 최신 세션 조회 (Bearer 인증) |
| `GET /agent` | 신규 (원라이너 채택 시) | 최신 부트스트랩 스크립트 반환 |
| `DELETE /api/session/by-owner/{id}` | 신규 | `slink disconnect` 시 정리 |

데이터 모델:
```java
class User {
  String studentId;     // "32211690"
  String email;         // "hyun@dankook.ac.kr"
  String apiKeyHash;    // 평문 저장 X, BCrypt 해시
  Instant createdAt;
  Instant expiresAt;    // 학기말 자동 만료
}

class Session {
  String code;          // 기존 6자리 — 폴백용 유지
  String owner;         // 신규 — 학번 또는 이메일
  String ngrokHost;
  String jupyterToken;
  Instant expiresAt;
}
```

## 시스템 책임 분담

| 누가 | 무엇을 | 빈도 |
|------|--------|------|
| 학생 | `slink init` + Colab Secret 입력 | 학기 초 1회 |
| 학생 | Colab 북마크 → Ctrl+F9 | 매일 사용 시 |
| Colab Agent | Secret 로드 → ngrok 기동 → Relay 등록 → keepalive 응답 | 매 실행 자동 |
| Relay 서버 | API Key 검증, 세션 매핑, 만료 정리 | 상시 |
| `slink connect` | 본인 세션 조회, VS Code 설정 갱신, 데몬 기동 | 매일 1줄 |

## 한계와 그 이유

| 한계 | 원인 | 완화 |
|------|------|------|
| Colab 1회 클릭 필요 | Google 정책 (콜드 부팅 API 없음) | 본질적 하한선 — 완화 불가 |
| 90분 무활동 끊김 | Colab 무료 티어 정책 | keepalive 데몬으로 회피 |
| ngrok URL 매번 변경 | ngrok 무료 티어 | Relay가 추상화 (학생 영향 없음) |
| 대용량 데이터 전송 | 네트워크 왕복 비용 | Colab 안에서 Drive/Kaggle 직접 다운로드 권장 |
| API Key 분실 시 | 사용자 실수 | `slink reset` 재발급 명령 |

## 디벨롭 우선순위

| 단계 | 작업 | 작업량 | 비고 |
|------|------|--------|------|
| 1 | Relay에 `User`, `owner` 필드, API Key 발급 | 2~3h | 백엔드 코어 |
| 2 | `slink init` / `slink connect`(인자 없이) / `slink whoami` | 2~3h | CLI 확장 |
| 3 | Colab Agent 패키지화 (PyPI **또는** 원라이너) | 반나절 | 둘 중 하나 선행 |
| 4 | VS Code workspace 자동 설정 | 1h | UX 큰 개선 |
| 5 | keepalive 데몬 | 1~2h | Colab 안 끊김 |
| 6 | GitHub Actions → PyPI 자동 배포 (PyPI 채택 시) | 1~2h | 운영 자동화 |
| 7 | 하이브리드 (원라이너 + PyPI 부트스트랩) | 1h | 두 방식 합류 |

**총 작업량 약 1.5~2일** — 학기 시작 전 충분히 끼울 수 있는 분량.

## 데모 미팅에서 보여줄 표면

```
[학생 Colab 빈 노트북에 1줄 붙여넣기]
!curl -sSL https://slink-production.up.railway.app/agent | python -

→ 1분 후 노트북에 ✓ Tesla T4 표시

[학생 VM 터미널]
$ slink connect
✓ Connected (Tesla T4, 세션 만료까지 11h 47m)

[VS Code]
torch.cuda.get_device_name()
>>> 'Tesla T4'
```

**Colab 1줄 + VM 1줄.** 이게 A안의 최종 모습입니다.
