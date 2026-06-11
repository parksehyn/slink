# 데모 실행 순서 (런북) — 외부 공개(역방향 터널링)

> 작성일: 2026-06-12
> 관련 문서: [demo-theory.md](demo-theory.md) (동작 원리), [internal-dns-requirements.md](internal-dns-requirements.md)

push 직후처럼 **Railway가 재배포되어 상태가 초기화된 상황**을 가정한, 처음부터 끝까지의 데모 실행 순서.

## 0. 왜 매번 재셋업인가 (전제)

Relay는 **모든 데이터를 메모리에만** 보관한다(DB 없음). `git push`(main) → Railway 자동 재배포 → 서버 재시작 → **API Key·서비스·Agent 등록 정보가 전부 초기화**된다.

→ 재배포 후에는 아래 순서를 **처음부터 다시** 밟아야 한다.
→ **미팅/데모 도중에는 push 금지.** 변경은 커밋만 하고 push는 데모 후에 한다. (커밋은 재배포를 일으키지 않음)

```
git push (main) → Railway 재배포 → 인메모리 초기화
  ❌ API Key 무효(401)   ❌ 서비스 목록 소멸   ❌ Agent 등록 소멸
```

## 1. 사전 준비 (1회 / 재배포와 무관)

VM에서 cloudflared 설치 확인:

```bash
which cloudflared && cloudflared --version
```

없으면 (Linux amd64):

```bash
wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O /tmp/cloudflared
chmod +x /tmp/cloudflared && sudo mv /tmp/cloudflared /usr/local/bin/cloudflared
```

소스 최신화 (agent 명령은 PyPI 미배포 → 레포의 `slink.py` 직접 실행):

```bash
cd ~/vscode/slink && git pull
cd agents/slink
python3 slink.py --help   # 선택지에 'agent'가 보이면 OK
```

## 2. (재배포 시) 사용자 등록 — 새 API Key 발급

```bash
cd ~/vscode/slink/agents/slink
python3 slink.py init
  학번:  32211690
  이메일: hyun@dankook.ac.kr
  → API Key: sk-dku-xxxx 발급, ~/.slinkrc 저장
```

> `Student ID already registered`(409)가 뜨면 그 학번이 이미 점유된 것.
> 다른 학번/이메일을 쓰거나, 기존 발급 키를 알고 있으면 `~/.slinkrc`에 직접 넣는다:
> ```bash
> cat > ~/.slinkrc <<'EOF'
> {"student_id":"32211690","email":"hyun@dankook.ac.kr","api_key":"sk-dku-xxxx","relay_url":"https://slink-production-3e7d.up.railway.app"}
> EOF
> ```

발급된 키를 **메모**해 둔다. 이후 포털 로그인에 같은 키를 쓴다.

## 3. 공개할 서비스 띄우기 (VM)

별도 터미널에서, 공개할 앱을 실제로 실행한다. 예시(테스트용):

```bash
python3 -m http.server 3000
```

실제 listen 포트 확인:

```bash
ss -ltnp | grep -E ':(3000|8000|8080|5000)'
```

→ 여기서 보이는 **포트 번호**를 포털 등록에 그대로 쓴다. (불일치 시 502)

## 4. VM Agent 시작 (켜둔 채 유지)

```bash
cd ~/vscode/slink/agents/slink
python3 slink.py agent start --instance-id solid-32211690
```

성공하면 `Agent ID ...`와 `Heartbeat 10초 간격` 배너가 뜬다. **이 터미널은 닫지 않는다** (Ctrl+C = Agent 종료).

## 5. 포털에서 서비스 등록·공개

브라우저에서 **Railway 포털**을 연다 (localhost 아님):

```
https://slink-production-3e7d.up.railway.app/portal/index.html
```

1. **API Key 입력** — 2단계에서 발급/저장한 키 (Agent와 같은 키여야 함)
2. **Add Service**
   - Instance: `solid-32211690`  ← Agent의 `--instance-id`와 정확히 일치
   - Local port: `3000`           ← VM에서 실제 listen 중인 포트
   - Private IP: VM의 `10.0.X.X`   ← (`ip a`로 확인; PUBLIC 터널엔 미사용이나 입력 필수)
   - Protocol: HTTP
   - Scope: INTERNAL 또는 PRIVATE  ← PUBLIC은 직접 못 고름
3. 서비스 상세 → **외부 공개 시작**
   - 상태 `PENDING` → 최대 10초 후 Agent 터미널에 `터널 열림: https://xxxx.trycloudflare.com`
   - 포털 상태 `PENDING → PUBLIC`, 공개 URL 표시
4. **시연**: 그 `trycloudflare.com` 주소를 **VPN 없는 환경(예: 휴대폰)**에서 열어 VM 서비스가 보이는 것을 확인.
5. **종료**: 포털 "외부 공개 종료" → Agent가 터널 닫음, URL 사라짐.

## 6. 체크리스트 — 세 값이 같은 키인지

```
포털 로그인 키  ==  ~/.slinkrc의 api_key  ==  Agent가 등록한 키
```

이 세 군데가 같아야 publish 명령이 Agent에 도달한다.

## 7. 자주 나는 문제

| 증상 | 원인 | 해결 |
|------|------|------|
| Agent 등록 시 `401 Unauthorized` | `~/.slinkrc` 키가 현재 Railway에 없음(재배포로 초기화) | `slink.py init` 재실행해 새 키 발급 |
| 포털 "API 키가 올바르지 않음" | **localhost 포털**을 열었거나(키는 Railway에만 있음) 키 오타 | Railway 포털 URL로 접속, 키 재확인 |
| `init` → `409 Conflict` | 학번/이메일이 이미 등록됨 | 다른 학번/이메일 사용 or `~/.slinkrc`에 기존 키 직접 기입 |
| 공개 눌러도 Agent 터미널 조용 | 명령이 안 옴 — instanceId/owner 불일치, 또는 서비스가 다른 키로 등록됨 | Instance 값·로그인 키가 Agent와 일치하는지 확인 |
| 공개 URL 접속 시 `502 Bad Gateway` | 등록 포트 ≠ VM 실제 listen 포트 (또는 서비스 안 떠 있음) | `ss -ltnp`로 포트 확인 → 포털 포트 수정 → **공개 다시 시작** |
| 포트 바꿨는데 여전히 502 | 터널은 열릴 때 포트로 고정됨 | 공개 종료 후 포트 수정 → 공개 다시 시작 |

## 8. 빠른 참조

```
Relay   : https://slink-production-3e7d.up.railway.app
포털    : https://slink-production-3e7d.up.railway.app/portal/index.html
VM      : ubuntu@solid-32211690 (instance-id: solid-32211690)
Agent   : python3 slink.py agent start --instance-id solid-32211690
서비스  : python3 -m http.server <PORT>
원리    : docs/demo-theory.md 참조
```
</content>
