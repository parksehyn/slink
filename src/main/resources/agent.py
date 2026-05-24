#!/usr/bin/env python3
"""slink-agent — Colab GPU 에이전트 부트스트랩 / Cloudflare Tunnel 버전 ({{RELAY_URL}} 에서 서빙)"""
import subprocess, sys, os, time, re, secrets as _secrets

RELAY_URL = "{{RELAY_URL}}"

# 1. 의존성 설치
print("[slink-agent] 패키지 설치 중...")
subprocess.run(
    [sys.executable, "-m", "pip", "install", "-q", "requests", "jupyterlab"],
    check=True,
)

import requests

# 2. cloudflared 바이너리 설치
CF_BIN = "/usr/local/bin/cloudflared"
if not os.path.exists(CF_BIN):
    print("[slink-agent] cloudflared 설치 중...")
    import urllib.request
    urllib.request.urlretrieve(
        "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64",
        CF_BIN,
    )
    os.chmod(CF_BIN, 0o755)

# 3. Colab Secret 로드
try:
    from google.colab import userdata
    api_key = userdata.get("SLINK_API_KEY") or ""
except Exception:
    api_key = os.environ.get("SLINK_API_KEY", "")

if not api_key:
    raise SystemExit("[slink-agent] 오류: Colab Secret에 SLINK_API_KEY를 등록하세요.")

# 4. Relay에서 이메일 조회
try:
    r = requests.get(
        f"{RELAY_URL}/api/users/me",
        headers={"Authorization": f"Bearer {api_key}"},
        timeout=10,
    )
except Exception as e:
    raise SystemExit(f"[slink-agent] 오류: Relay 서버에 연결할 수 없습니다 ({e})")

if r.status_code == 401:
    raise SystemExit("[slink-agent] 오류: API Key가 올바르지 않습니다. slink init을 다시 실행하세요.")
r.raise_for_status()
email = r.json()["email"]
print(f"[slink-agent] 사용자: {email}")

# 5. 이전 cloudflared 종료
subprocess.run(["pkill", "-9", "-f", "cloudflared"], capture_output=True)
time.sleep(1)

# 6. JupyterLab 시작
jupyter_token = _secrets.token_hex(16)
print("[slink-agent] JupyterLab 시작 중...")
subprocess.Popen([
    sys.executable, "-m", "jupyter", "lab",
    "--no-browser", "--port=8899",
    f"--ServerApp.token={jupyter_token}",
    "--ServerApp.allow_origin=*",
    "--ip=0.0.0.0",
])
time.sleep(6)

# 7. Cloudflare Quick Tunnel
print("[slink-agent] Cloudflare 터널 시작 중...")
cf_proc = subprocess.Popen(
    [CF_BIN, "tunnel", "--url", "http://localhost:8899"],
    stderr=subprocess.PIPE,
    stdout=subprocess.DEVNULL,
)

tunnel_url = None
for line in cf_proc.stderr:
    m = re.search(r'https://[a-z0-9-]+\.trycloudflare\.com', line.decode())
    if m:
        tunnel_url = m.group(0)
        break

if not tunnel_url:
    raise SystemExit("[slink-agent] 오류: Cloudflare 터널 URL을 가져오지 못했습니다.")

# 8. Relay 등록
resp = requests.post(f"{RELAY_URL}/api/session/register", json={
    "owner": email,
    "ngrokHost": tunnel_url,
    "sshPort": 0,
    "otp": "",
    "jupyterToken": jupyter_token,
}, timeout=10)
resp.raise_for_status()
code = resp.json()["code"]

print(f"\n{'=' * 52}")
print(f"  ✓ Colab GPU 준비 완료!")
print(f"  연결 코드 : {code}")
print(f"  Jupyter  : {tunnel_url}")
print(f"  VM에서   : slink connect")
print(f"{'=' * 52}\n")

# 9. 유지 (런타임 중지 버튼으로 종료)
print("[slink-agent] 실행 중...")
try:
    while True:
        time.sleep(60)
except KeyboardInterrupt:
    try:
        requests.delete(
            f"{RELAY_URL}/api/session/by-owner/{email}",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=5,
        )
    except Exception:
        pass
    cf_proc.terminate()
    print("[slink-agent] 세션 종료")
