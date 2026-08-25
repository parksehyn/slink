#!/usr/bin/env python3
"""slink-agent — 외부 자원 연결 에이전트 (Colab GPU / 외부 Jupyter / HTTP·API)

외부 환경에서 자율적으로 Cloudflare 터널을 열고, 자기 공개 URL·토큰·만료를 Relay에 self-report 한다.
SOLID는 OPEN 명령을 내리지 않는다(에이전트가 자기 URL의 source of truth).

인증:
  - 임시(Colab 등): 포털이 발급한 단기 등록 토큰 rt-  (env SLINK_REG_TOKEN / Colab Secret)
  - 디바이스(연구실 GPU): 최초 rt-로 enroll 후 rat-/resourceId를 파일에 저장 → 이후 register 생략
  SOLID 비밀번호·장기 키를 외부에 저장하지 않는다.

환경변수:
  SLINK_RELAY          Relay 주소 (기본 아래 RELAY_DEFAULT)
  SLINK_REG_TOKEN      rt- 등록 토큰 (임시 경로)
  SLINK_RESOURCE_TYPE  COLAB_GPU | JUPYTER | HTTP_API (기본 COLAB_GPU)
  SLINK_RESOURCE_NAME  자원 이름 (참고용; 서버는 토큰에 묶인 이름을 사용)
  SLINK_LOCAL_PORT     JUPYTER/HTTP_API가 터널링할 로컬 포트 (기본 Jupyter=8888, HTTP=8000)
  SLINK_SERVICE_TOKEN  JUPYTER 접근 토큰(선택)
  SLINK_AGENT_STATE    디바이스 토큰 저장 경로 (기본 ~/.slink-resource.json)
"""

import json
import os
import re
import subprocess
import sys
import time
import secrets
from datetime import datetime, timedelta, timezone

import requests

RELAY_DEFAULT = "https://slink-production-3e7d.up.railway.app"
CF_BIN = "/usr/local/bin/cloudflared"
POLL_INTERVAL = 10  # heartbeat 주기(초) — 서버 liveness 윈도우(60s)와 정합
COLAB_TTL_HOURS = 12


def _load_secret(key: str) -> str:
    try:
        from google.colab import userdata
        return userdata.get(key) or ""
    except Exception:
        return os.environ.get(key, "")


def _state_path() -> str:
    return os.environ.get("SLINK_AGENT_STATE", os.path.expanduser("~/.slink-resource.json"))


def _install_cloudflared():
    if os.path.exists(CF_BIN):
        return
    print("[slink-agent] cloudflared 설치 중...")
    import urllib.request
    urllib.request.urlretrieve(
        "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64",
        CF_BIN,
    )
    os.chmod(CF_BIN, 0o755)


def _start_jupyter() -> tuple[str, int]:
    """COLAB_GPU: JupyterLab을 8899에 기동하고 (token, port) 반환."""
    token = secrets.token_hex(16)
    print("[slink-agent] JupyterLab 시작 중...")
    subprocess.Popen([
        sys.executable, "-m", "jupyter", "lab",
        "--no-browser", "--port=8899",
        f"--ServerApp.token={token}",
        "--ServerApp.allow_origin=*",
        "--ip=0.0.0.0",
    ])
    time.sleep(6)
    return token, 8899


def _open_tunnel(local_port: int):
    """cloudflared Quick Tunnel 개통. (proc, url) 반환."""
    print(f"[slink-agent] Cloudflare 터널 시작 중 (localhost:{local_port})...")
    proc = subprocess.Popen(
        [CF_BIN, "tunnel", "--url", f"http://localhost:{local_port}"],
        stderr=subprocess.PIPE, stdout=subprocess.DEVNULL,
    )
    url = None
    for line in proc.stderr:
        m = re.search(r"https://[a-z0-9-]+\.trycloudflare\.com", line.decode(errors="ignore"))
        if m:
            url = m.group(0)
            break
    if not url:
        proc.terminate()
        raise SystemExit("[slink-agent] 오류: Cloudflare 터널 URL을 가져오지 못했습니다.")
    return proc, url


def _register(relay: str, rt: str, resource_type: str, name: str,
              public_url: str, service_token: str, expires_at: str) -> dict:
    resp = requests.post(
        f"{relay}/api/resources/agents/register",
        headers={"X-Registration-Token": rt},
        json={
            "resourceType": resource_type,
            "name": name,
            "publicUrl": public_url,
            "serviceToken": service_token,
            "expiresAt": expires_at,
        },
        timeout=15,
    )
    if resp.status_code == 401:
        raise SystemExit("[slink-agent] 오류: 등록 토큰(rt-)이 만료됐거나 이미 사용됐습니다. 포털에서 다시 발급하세요.")
    resp.raise_for_status()
    return resp.json()


def _report(relay: str, resource_id: str, rat: str, event: str, **kwargs):
    body = {"event": event}
    body.update({k: v for k, v in kwargs.items() if v is not None})
    try:
        requests.post(
            f"{relay}/api/resources/agents/{resource_id}/report",
            headers={"X-Agent-Token": rat}, json=body, timeout=10,
        )
    except Exception as e:
        print(f"[slink-agent] report({event}) 실패: {e}")


def _heartbeat(relay: str, resource_id: str, rat: str):
    """반환: revoked(bool). 자원이 사라졌으면(401) True로 간주해 종료시킨다."""
    try:
        r = requests.post(
            f"{relay}/api/resources/agents/{resource_id}/heartbeat",
            headers={"X-Agent-Token": rat}, timeout=10,
        )
        if r.status_code == 401:
            return True
        if r.ok:
            return bool(r.json().get("revoked"))
    except Exception as e:
        print(f"[slink-agent] heartbeat 실패(무시): {e}")
    return False


def run(relay: str):
    resource_type = os.environ.get("SLINK_RESOURCE_TYPE", "COLAB_GPU").upper()
    name = os.environ.get("SLINK_RESOURCE_NAME", "")
    _install_cloudflared()
    subprocess.run(["pkill", "-9", "-f", "cloudflared"], capture_output=True)
    time.sleep(1)

    # 1. 서비스 기동 + 로컬 포트·서비스 토큰 결정
    service_token = os.environ.get("SLINK_SERVICE_TOKEN", "") or None
    if resource_type == "COLAB_GPU":
        service_token, local_port = _start_jupyter()
        expires_at = (datetime.now(timezone.utc) + timedelta(hours=COLAB_TTL_HOURS)).isoformat()
    elif resource_type == "JUPYTER":
        local_port = int(os.environ.get("SLINK_LOCAL_PORT", "8888"))
        expires_at = None
    else:  # HTTP_API
        local_port = int(os.environ.get("SLINK_LOCAL_PORT", "8000"))
        expires_at = None

    # 2. 터널 개통
    cf_proc, tunnel_url = _open_tunnel(local_port)

    # 3. 등록(rt-) 또는 디바이스(rat-) 토큰 로드
    state_path = _state_path()
    rt = os.environ.get("SLINK_REG_TOKEN") or _load_secret("SLINK_REG_TOKEN")
    device = None
    if not rt and os.path.exists(state_path):
        try:
            with open(state_path) as f:
                device = json.load(f)
        except Exception:
            device = None

    if device and device.get("resourceId") and device.get("agentToken"):
        resource_id, rat = device["resourceId"], device["agentToken"]
        print(f"[slink-agent] 디바이스 토큰으로 재개 (resource={resource_id})")
        _report(relay, resource_id, rat, "RESOURCE_READY",
                publicUrl=tunnel_url, serviceToken=service_token, expiresAt=expires_at)
    elif rt:
        reg = _register(relay, rt, resource_type, name, tunnel_url, service_token, expires_at)
        resource_id, rat = reg["resourceId"], reg["agentToken"]
        # 디바이스 영속(연구실 GPU 재부팅 생존). Colab은 임시라도 저장해두면 재실행 시 카드 갱신.
        try:
            with open(state_path, "w") as f:
                json.dump({"resourceId": resource_id, "agentToken": rat, "relay": relay}, f)
            os.chmod(state_path, 0o600)
        except Exception:
            pass
    else:
        cf_proc.terminate()
        raise SystemExit("[slink-agent] 오류: SLINK_REG_TOKEN(rt-)이 필요합니다. 포털 '외부 자원 추가'에서 발급하세요.")

    print(f"\n{'=' * 52}")
    print(f"  ✓ 외부 자원 준비 완료! ({resource_type})")
    print(f"  자원 ID : {resource_id}")
    print(f"  URL     : {tunnel_url}")
    if service_token:
        print(f"  토큰    : {service_token}")
    print(f"  SOLID에서: 포털 '연결' 또는 slink connect --resource {resource_id}")
    print(f"{'=' * 52}\n")

    # 4. heartbeat 루프 (revoked면 종료, 터널 죽으면 재개통→재보고)
    print("[slink-agent] 실행 중... (Ctrl+C 종료)")
    try:
        while True:
            time.sleep(POLL_INTERVAL)
            if _heartbeat(relay, resource_id, rat):
                print("[slink-agent] SOLID측에서 자원이 중지/삭제됨 → 종료")
                break
            if cf_proc.poll() is not None:  # 터널 죽음 → 재개통
                print("[slink-agent] 터널 끊김 → 재개통")
                cf_proc, tunnel_url = _open_tunnel(local_port)
                _report(relay, resource_id, rat, "RESOURCE_READY",
                        publicUrl=tunnel_url, serviceToken=service_token, expiresAt=expires_at)
    except KeyboardInterrupt:
        print("\n[slink-agent] 종료 중...")
    finally:
        _report(relay, resource_id, rat, "RESOURCE_STOPPED")
        try:
            cf_proc.terminate()
        except Exception:
            pass
        print("[slink-agent] 종료됨")


def main():
    relay = os.environ.get("SLINK_RELAY", RELAY_DEFAULT).rstrip("/")
    run(relay)


if __name__ == "__main__":
    main()
