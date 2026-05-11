#!/usr/bin/env python3
"""slink-agent — Colab GPU 에이전트"""

import os
import subprocess
import sys
import time
import secrets

import requests
from pyngrok import ngrok, conf

RELAY_DEFAULT = "https://slink-production-3e7d.up.railway.app"


def _load_secret(key: str) -> str:
    try:
        from google.colab import userdata
        return userdata.get(key) or ""
    except Exception:
        return os.environ.get(key, "")


def run(relay_url: str):
    # 1. API Key 로드
    api_key = _load_secret("SLINK_API_KEY")
    if not api_key:
        raise SystemExit("[slink-agent] 오류: Colab Secret에 SLINK_API_KEY를 등록하세요.")

    # 2. Relay에서 이메일 조회
    try:
        r = requests.get(
            f"{relay_url}/api/users/me",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=10,
        )
    except Exception as e:
        raise SystemExit(f"[slink-agent] 오류: Relay 서버 연결 실패 ({e})")

    if r.status_code == 401:
        raise SystemExit("[slink-agent] 오류: API Key가 올바르지 않습니다. slink init을 다시 실행하세요.")
    r.raise_for_status()
    email = r.json()["email"]
    print(f"[slink-agent] 사용자: {email}")

    # 3. 이전 ngrok 종료
    try:
        ngrok.kill()
    except Exception:
        pass
    subprocess.run(["pkill", "-9", "-f", "ngrok"], capture_output=True)
    time.sleep(1)

    # 4. ngrok 인증 토큰 (선택 — 없어도 동작)
    ngrok_token = _load_secret("NGROK_AUTHTOKEN")
    if ngrok_token:
        conf.get_default().auth_token = ngrok_token

    # 5. JupyterLab 시작
    jupyter_token = secrets.token_hex(16)
    print("[slink-agent] JupyterLab 시작 중...")
    subprocess.Popen([
        sys.executable, "-m", "jupyter", "lab",
        "--no-browser", "--port=8899",
        f"--ServerApp.token={jupyter_token}",
        "--ServerApp.allow_origin=*",
        "--ip=0.0.0.0",
    ])
    time.sleep(6)

    # 6. ngrok 터널
    print("[slink-agent] ngrok 터널 시작 중...")
    tunnel = ngrok.connect(8899, "http")
    ngrok_host = tunnel.public_url

    # 7. Relay 등록
    resp = requests.post(f"{relay_url}/api/session/register", json={
        "owner": email,
        "ngrokHost": ngrok_host,
        "sshPort": 0,
        "otp": "",
        "jupyterToken": jupyter_token,
    }, timeout=10)
    resp.raise_for_status()
    code = resp.json()["code"]

    print(f"\n{'=' * 52}")
    print(f"  ✓ Colab GPU 준비 완료!")
    print(f"  연결 코드 : {code}")
    print(f"  Jupyter  : {ngrok_host}")
    print(f"  VM에서   : slink connect")
    print(f"{'=' * 52}\n")

    # 8. 유지 (런타임 중지 버튼으로 종료)
    print("[slink-agent] 실행 중...")
    try:
        while True:
            time.sleep(60)
    except KeyboardInterrupt:
        try:
            requests.delete(
                f"{relay_url}/api/session/by-owner/{email}",
                headers={"Authorization": f"Bearer {api_key}"},
                timeout=5,
            )
        except Exception:
            pass
        ngrok.kill()
        print("[slink-agent] 세션 종료")


def main():
    relay_url = os.environ.get("SLINK_RELAY", RELAY_DEFAULT).rstrip("/")
    run(relay_url)


if __name__ == "__main__":
    main()
