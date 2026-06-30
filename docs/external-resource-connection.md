# 외부 자원 연결 설계

> 작성일: 2026-06-30  
> 상태: 방향 확정, 포털 통합은 후속 구현  
> 관련 문서: [tabs-redesign.md](tabs-redesign.md), [todo-next.md](todo-next.md), [progress.md](progress.md)

## 1. 제품 방향

포털의 기존 `아웃바운드` 기능을 네트워크 용어가 아닌 사용자 목적 중심의 **외부 자원 연결**로 재정의한다.

```text
SOLID에서 외부 개발·연산 자원을 사용
├─ Google Colab GPU
├─ 외부 Jupyter 서버
├─ 연구실/개인 GPU 워크스테이션
└─ 외부 HTTP/API 서비스
```

단순 웹 URL 북마크 기능이 목적은 아니다. 주소가 임시로 바뀌거나 토큰·만료·상태 관리가 필요한
외부 개발 자원을 SOLID 환경에서 반복적으로 연결하는 것이 핵심 가치다.

사용자 화면에서는 다음 용어를 사용한다.

```text
기존: 아웃바운드 (외부 → 접근)   → 외부 자원 연결
기존: 인바운드 (외부 ← SOLID)    → SOLID 서비스 공개
```

## 2. 공통 동작 원리

Colab GPU와 다른 외부 자원은 다음 기반 흐름을 공유할 수 있다.

```text
[외부 환경]
서비스 실행 → cloudflared 실행 → 공개 URL 생성 → Relay 등록

[SOLID]
포털/CLI에서 자원 조회 → URL·토큰·상태 확인 → 외부 자원 사용
```

Relay는 실제 서비스 트래픽을 중계하지 않는다. URL, 토큰, 소유자, 만료시간 등 연결 정보를
관리하는 컨트롤 플레인이다. 실제 트래픽은 SOLID VM에서 외부 URL로 직접 나간다.

## 3. Colab과 Jupyter의 관계

- **Google Colab**: Google이 제공하는 GPU 실행 환경
- **JupyterLab**: 그 실행 환경의 Python/GPU를 원격으로 사용하는 웹 인터페이스

현재 slink는 Colab 런타임 안에서 JupyterLab을 실행하고 Cloudflare Tunnel로 공개한 뒤,
SOLID VM의 VS Code가 해당 Jupyter 서버를 커널로 사용하게 한다.

```text
Colab GPU 런타임 → JupyterLab → Cloudflare Tunnel → SOLID VM VS Code
```

따라서 Colab은 `JUPYTER` 자원 유형의 대표 사례지만, Colab 자체와 Jupyter는 같은 개념이 아니다.

## 4. 자원 유형별 차이

터널 생성과 Relay 등록은 공유할 수 있지만 모든 자원이 Colab과 완전히 동일하게 동작하지는 않는다.

| 자원 | 공통 정보 | 추가 처리 |
|---|---|---|
| Colab GPU | URL, 소유자, 만료 | GPU 런타임, JupyterLab 자동 기동, Token, VS Code 연결 |
| 외부 Jupyter | URL, Token, 만료 | Jupyter 헬스체크, VS Code 연결 안내 |
| HTTP/API | URL, 상태 | HTTP 헬스체크, 바로 열기 |
| SSH/DB | 호스트, 포트, 인증 | TCP 터널·보안 정책 필요. Quick Tunnel HTTP 흐름을 그대로 사용 불가 |

1차 일반화 대상은 **Colab GPU, 외부 Jupyter, HTTP/API**로 제한한다. SSH와 DB는 별도 TCP 연결 및
접근 통제 설계 후 추가한다.

## 5. 현재 구현 상태

### 실제 동작

- 기존 Colab Agent가 JupyterLab과 Cloudflare Quick Tunnel을 생성
- `/api/session/*`에 Colab 세션 등록
- `slink connect`로 SOLID VM에서 Colab Jupyter/GPU 연결
- 포털 `/api/connections`에서 일반 외부 URL·토큰·메모 등록/조회/삭제
- 아웃바운드 연결 정보 파일 영속(`connection.store.file`)

### 현재 포털 통합의 한계

새 포털과 기존 Colab 세션은 인증 체계가 다르다.

```text
새 포털/DNS/터널링: SOLID 로그인 토큰 slk-...
기존 Colab 세션:     slink 자체 API Key sk-dku-...
```

포털의 `loadColabSession()`은 `/api/session/by-owner/{email}`을 호출하지만, 이 API는 아직
`sk-dku-` 키를 검증한다. 포털은 `slk-` 토큰을 보내므로 현재 Colab 세션 자동 카드는 401이 되어
표시되지 않는다.

결론:

```text
기존 CLI 기반 Colab GPU 연결       실제 동작
포털의 일반 외부 URL 수동 등록      실제 동작
포털에서 Colab 세션 자동 조회       미동작(인증 불일치)
포털 클릭만으로 VS Code GPU 연결    미구현
일반 외부 자원 자동 Agent           미구현
```

## 6. 목표 사용자 흐름

### Colab GPU

```text
1. 포털에서 SOLID 로그인
2. 외부 자원 연결 → Colab GPU 선택
3. Colab에서 포털이 발급한 자원 등록 토큰으로 Agent 실행
4. 포털에 GPU/Jupyter 상태·URL·만료시간 자동 표시
5. SOLID VM에서 연결 명령 실행 또는 VS Code 연결 안내 사용
```

### 외부 Jupyter/HTTP

```text
1. 외부 환경에서 서비스 실행
2. 공통 slink 외부 자원 Agent가 cloudflared 실행
3. Agent가 자원 유형·URL·Token·만료시간을 Relay에 등록
4. 포털에서 상태 확인 후 SOLID에서 연결
```

## 7. 후속 구현 계획

1. 포털 용어를 `외부 자원 연결` / `SOLID 서비스 공개`로 변경
2. Colab 세션 소유권을 SOLID 계정(`ownerId=account`)과 연결
3. Colab Agent용 단기 등록 토큰을 포털에서 발급
   - SOLID 비밀번호를 Colab Secret에 저장하지 않음
   - 장기 `sk-dku-` 키를 단계적으로 제거
4. `/api/session`과 `/api/connections`의 중복 모델을 정리
   - 기존 `slink connect` 호환을 유지하면서 공통 ExternalResource 모델 도입
5. 자원 유형별 어댑터 추가
   - `COLAB_GPU`, `JUPYTER`, `HTTP_API`
6. 포털에 상태·만료·연결 안내 표시
7. 인증 회귀, 소유권 격리, 만료·삭제, 기존 CLI 호환 테스트 추가

## 8. 구현 시 지켜야 할 원칙

- 외부 자원 URL을 단순 저장하는 기능만으로 완료 처리하지 않는다.
- SOLID 비밀번호를 외부 환경이나 장기 설정 파일에 저장하지 않는다.
- 토큰은 소유자·자원·만료시간에 묶고 철회 가능해야 한다.
- 기존 Colab `slink connect` 흐름을 깨지 않고 단계적으로 이전한다.
- Quick Tunnel URL을 아는 사람은 접근할 수 있으므로 민감 자원은 별도 인증을 적용한다.
- SSH/DB를 HTTP/Jupyter와 같은 방식으로 지원한다고 약속하지 않는다.

## 9. 우선순위

```text
1. 내부 DNS 실운영 안정화
2. SOLID 서비스 외부 공개(인바운드) 운영 자동화
3. 기존 Colab GPU를 새 포털 인증에 통합
4. 외부 Jupyter/HTTP 자원으로 일반화
5. SSH/DB는 수요와 보안 정책 확인 후 별도 설계
```

