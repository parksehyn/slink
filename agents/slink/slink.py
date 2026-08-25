#!/usr/bin/env python3
"""slink - SOLID VM <-> Colab GPU 연결 CLI"""

import argparse
import getpass
import json
import os
import shutil
import signal
import sys
import threading
import time
from datetime import datetime, timezone

import requests

RELAY_ENV = "SLINK_RELAY"
RELAY_DEFAULT = "https://slink-production-3e7d.up.railway.app"
SLINKRC = os.path.expanduser("~/.slinkrc")
SLINKPID = os.path.expanduser("~/.slink.pid")


# ── config ────────────────────────────────────────────────────────────────────

def load_config() -> dict:
    if not os.path.exists(SLINKRC):
        print("[slink] 오류: 설정 파일이 없습니다. 먼저 'slink init'을 실행하세요.")
        sys.exit(1)
    with open(SLINKRC, encoding="utf-8") as f:
        return json.load(f)


def save_config(cfg: dict):
    with open(SLINKRC, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def load_config_optional() -> dict:
    """rc 파일이 없어도 빈 설정으로 동작. SOLID 인증 기반 명령(agent start 등)은
    `slink init`(레거시 Colab sk-dku- 등록)이 필요 없으므로 하드 종료하지 않는다."""
    if not os.path.exists(SLINKRC):
        return {}
    with open(SLINKRC, encoding="utf-8") as f:
        return json.load(f)


# ── daemon helpers ─────────────────────────────────────────────────────────────

def _read_pid() -> int | None:
    if not os.path.exists(SLINKPID):
        return None
    try:
        with open(SLINKPID) as f:
            return int(f.read().strip())
    except (ValueError, OSError):
        return None


def _is_daemon_running() -> tuple[bool, int | None]:
    pid = _read_pid()
    if pid is None:
        return False, None
    try:
        os.kill(pid, 0)
        return True, pid
    except ProcessLookupError:
        os.remove(SLINKPID)
        return False, None


def _write_pid(pid: int):
    with open(SLINKPID, "w") as f:
        f.write(str(pid))


def _remove_pid():
    try:
        os.remove(SLINKPID)
    except FileNotFoundError:
        pass


# ── commands ──────────────────────────────────────────────────────────────────

def cmd_init(args):
    relay_url = args.relay.rstrip("/")

    print("[slink] 사용자 등록을 시작합니다.")
    student_id = input("  학번: ").strip()
    email = input("  이메일: ").strip()

    if not student_id or not email:
        print("[slink] 오류: 학번과 이메일을 모두 입력해야 합니다.")
        sys.exit(1)

    try:
        resp = requests.post(
            f"{relay_url}/api/users/register",
            json={"studentId": student_id, "email": email},
            timeout=10,
        )
    except requests.ConnectionError:
        print(f"[slink] 오류: Relay 서버에 연결할 수 없습니다 ({relay_url})")
        sys.exit(1)

    resp.raise_for_status()
    data = resp.json()
    api_key = data["apiKey"]

    cfg = {
        "student_id": student_id,
        "email": email,
        "api_key": api_key,
        "relay_url": relay_url,
    }
    save_config(cfg)

    print(f"[slink] ✓ 등록 완료")
    print(f"[slink] API Key: {api_key}")
    print(f"[slink] 설정 저장: {SLINKRC}")
    print()
    print("  [Colab GPU 연결]")
    print("  Colab 좌측 🔑 → 새 보안 비밀 추가")
    print(f"  이름: SLINK_API_KEY  /  값: {api_key}")
    print()
    print("  [서비스 포털]")
    print(f"  {relay_url}/portal/")
    print()
    print("  [VM 서비스 외부 공개]")
    print("  SOLID VM에서: slink agent start --instance-id <solid-XXXX>")
    print("  (에이전트 등록은 SOLID 로그인 사용 — 실행 시 SOLID 비밀번호를 입력합니다)")


def cmd_whoami(args):
    cfg = load_config()
    print(f"[slink] 학번   : {cfg['student_id']}")
    print(f"[slink] 이메일 : {cfg['email']}")
    print(f"[slink] Relay  : {cfg['relay_url']}")


def fetch_session(code: str, relay_url: str) -> dict:
    try:
        resp = requests.get(f"{relay_url}/api/session/{code}", timeout=10)
    except requests.ConnectionError:
        print(f"[slink] 오류: Relay 서버에 연결할 수 없습니다 ({relay_url})")
        sys.exit(1)

    if resp.status_code == 404:
        print(f"[slink] 오류: 코드 {code}를 찾을 수 없습니다. Colab이 실행 중인지 확인하세요.")
        sys.exit(1)

    resp.raise_for_status()
    return resp.json()


def sync_files_http(jupyter_base_url: str, token: str, local_dir: str):
    headers = {
        "Authorization": f"token {token}",
        "ngrok-skip-browser-warning": "true",
    }
    SKIP = {'.git', '__pycache__', '.ipynb_checkpoints'}
    uploaded = 0

    for root, dirs, files in os.walk(local_dir):
        dirs[:] = [d for d in dirs if d not in SKIP]
        for fname in files:
            if fname.endswith('.pyc'):
                continue
            local_path = os.path.join(root, fname)
            rel = os.path.relpath(local_path, local_dir).replace("\\", "/")

            with open(local_path, 'rb') as f:
                content = f.read()

            try:
                content_str = content.decode('utf-8')
                fmt = "text"
            except UnicodeDecodeError:
                import base64
                content_str = base64.b64encode(content).decode()
                fmt = "base64"

            payload = {"type": "file", "format": fmt, "content": content_str}
            url = f"{jupyter_base_url}/api/contents/{rel}"
            r = requests.put(url, json=payload, headers=headers, timeout=30)
            if r.status_code in (200, 201):
                print(f"  -> {rel}")
                uploaded += 1
            else:
                print(f"  !! {rel} 업로드 실패 ({r.status_code})")

    print(f"[slink] 파일 {uploaded}개 동기화 완료")


def format_remaining(expires_at_str: str) -> str:
    try:
        expires_at = datetime.fromisoformat(expires_at_str.replace("Z", "+00:00"))
        secs = int((expires_at - datetime.now(timezone.utc)).total_seconds())
        if secs <= 0:
            return "만료됨"
        return f"{secs // 3600}h {(secs % 3600) // 60}m"
    except Exception:
        return "알 수 없음"


def update_vscode_settings(jupyter_base_url: str, token: str):
    vscode_dir = os.path.join(os.getcwd(), ".vscode")
    os.makedirs(vscode_dir, exist_ok=True)
    settings_path = os.path.join(vscode_dir, "settings.json")

    settings = {}
    if os.path.exists(settings_path):
        with open(settings_path, encoding="utf-8") as f:
            try:
                settings = json.load(f)
            except json.JSONDecodeError:
                pass

    settings["jupyter.jupyterServerType"] = "remote"
    settings["jupyter.existingJupyterServer.uri"] = f"{jupyter_base_url}/?token={token}"

    with open(settings_path, "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=2)

    print(f"[slink] VS Code 설정 갱신: {settings_path}")


def _keepalive_loop(jupyter_base_url: str, token: str, interval: int = 600):
    headers = {
        "Authorization": f"token {token}",
        "ngrok-skip-browser-warning": "true",
    }
    while True:
        time.sleep(interval)
        try:
            requests.get(f"{jupyter_base_url}/api", headers=headers, timeout=10)
        except Exception:
            pass


def start_keepalive(jupyter_base_url: str, token: str):
    t = threading.Thread(target=_keepalive_loop, args=(jupyter_base_url, token), daemon=True)
    t.start()
    print("[slink] keepalive 데몬 시작 (10분마다 ping)")


def print_connection_info(session: dict):
    jupyter_base_url = session["ngrokHost"].rstrip("/")
    token = session["jupyterToken"]
    remaining = format_remaining(session.get("expiresAt", ""))

    print()
    print("=" * 62)
    print(f"  [slink] ✓ Connected  (세션 만료까지 {remaining})")
    print()
    print(f"  URL  : {jupyter_base_url}")
    print(f"  Token: {token}")
    print("=" * 62)


def _get_session(args) -> tuple[dict, str]:
    if args.relay != RELAY_DEFAULT or not os.path.exists(SLINKRC):
        relay_url = args.relay.rstrip("/")
        email = None
        api_key = None
    else:
        cfg = load_config()
        relay_url = cfg["relay_url"].rstrip("/")
        email = cfg["email"]
        api_key = cfg["api_key"]

    if args.code:
        print(f"[slink] 코드 {args.code} 조회 중...")
        return fetch_session(args.code, relay_url), relay_url

    if not email or not api_key:
        print("[slink] 오류: 먼저 'slink init'을 실행하거나 --relay와 코드를 지정하세요.")
        sys.exit(1)

    print(f"[slink] {email} 세션 조회 중...")
    try:
        resp = requests.get(
            f"{relay_url}/api/session/by-owner/{email}",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=10,
        )
    except requests.ConnectionError:
        print(f"[slink] 오류: Relay 서버에 연결할 수 없습니다 ({relay_url})")
        sys.exit(1)

    if resp.status_code == 401:
        print("[slink] 오류: 인증 실패. API Key를 확인하세요.")
        sys.exit(1)
    if resp.status_code == 404:
        print("[slink] 오류: 등록된 세션이 없습니다. Colab을 먼저 실행하세요.")
        sys.exit(1)
    resp.raise_for_status()
    return resp.json(), relay_url


def _solid_login(relay_url: str, username: str, password: str, domain: str) -> str:
    """SOLID(CloudStack) 로그인 → slk- 토큰. 외부 자원 연결(--resource)에 사용."""
    cfg = load_config_optional()
    username = username or cfg.get("student_id") or ""
    if not username:
        username = input("  SOLID 학번/계정: ").strip()
    if domain is None:
        domain = cfg.get("domain", "")
    if not password:
        password = os.getenv("SLINK_SOLID_PASSWORD")
    if not password:
        password = getpass.getpass(f"  SOLID 비밀번호 ({username}): ")
    try:
        resp = requests.post(
            f"{relay_url}/api/auth/login",
            json={"username": username, "password": password, "domain": domain or ""},
            timeout=10,
        )
    except requests.ConnectionError:
        print(f"[slink] 오류: Relay 서버에 연결할 수 없습니다 ({relay_url})")
        sys.exit(1)
    if resp.status_code == 401:
        print("[slink] 오류: SOLID 로그인 실패. 학번/비밀번호/도메인을 확인하세요.")
        sys.exit(1)
    resp.raise_for_status()
    return resp.json()["token"]


def _get_resource(args) -> tuple[dict, str]:
    """외부 자원(--resource)을 SOLID 인증으로 조회해 connect 흐름이 기대하는 세션 형태로 매핑."""
    relay_url = args.relay.rstrip("/")
    token = _solid_login(relay_url, args.username, args.password, args.domain)
    print(f"[slink] 외부 자원 {args.resource} 조회 중...")
    try:
        resp = requests.get(
            f"{relay_url}/api/resources/{args.resource}",
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
    except requests.ConnectionError:
        print(f"[slink] 오류: Relay 서버에 연결할 수 없습니다 ({relay_url})")
        sys.exit(1)
    if resp.status_code == 401:
        print("[slink] 오류: SOLID 인증 실패.")
        sys.exit(1)
    if resp.status_code == 404:
        print("[slink] 오류: 자원을 찾을 수 없습니다(소유자만 접근 가능).")
        sys.exit(1)
    resp.raise_for_status()
    r = resp.json()
    if not r.get("publicUrl"):
        print(f"[slink] 오류: 자원이 아직 준비되지 않았습니다(상태={r.get('status')}). 외부 에이전트가 실행 중인지 확인하세요.")
        sys.exit(1)
    session = {
        "ngrokHost": r["publicUrl"],
        "jupyterToken": r.get("serviceToken") or "",
        "expiresAt": r.get("expiresAt") or "",
    }
    return session, relay_url


def cmd_connect(args):
    if getattr(args, "resource", None):
        session, _ = _get_resource(args)
    else:
        session, _ = _get_session(args)
    jupyter_base_url = session["ngrokHost"].rstrip("/")
    token = session["jupyterToken"]

    if args.sync_dir != ".":
        sync_dir = os.path.abspath(args.sync_dir)
        print(f"[slink] 파일 동기화 중: {sync_dir} -> Colab")
        sync_files_http(jupyter_base_url, token, sync_dir)

    update_vscode_settings(jupyter_base_url, token)

    if args.daemon:
        if not hasattr(os, "fork"):
            print("[slink] 오류: 백그라운드 실행은 Linux/macOS에서만 지원됩니다.")
            sys.exit(1)

        running, existing_pid = _is_daemon_running()
        if running:
            print(f"[slink] 이미 백그라운드 실행 중입니다 (PID: {existing_pid})")
            print(f"[slink] 종료하려면: slink disconnect")
            sys.exit(1)

        pid = os.fork()
        if pid > 0:
            # 부모: PID 기록 후 종료
            _write_pid(pid)
            print_connection_info(session)
            print(f"\n[slink] 백그라운드 실행 중 (PID: {pid})")
            print(f"[slink] 종료하려면: slink disconnect")
            sys.exit(0)

        # 자식: 터미널 분리 후 keepalive 실행
        os.setsid()
        with open(os.devnull, "r+") as devnull:
            os.dup2(devnull.fileno(), sys.stdin.fileno())
            os.dup2(devnull.fileno(), sys.stdout.fileno())
            os.dup2(devnull.fileno(), sys.stderr.fileno())

        signal.signal(signal.SIGTERM, lambda *_: (_remove_pid(), sys.exit(0)))
        _keepalive_loop(jupyter_base_url, token)
        return

    # 포그라운드 모드
    start_keepalive(jupyter_base_url, token)
    print_connection_info(session)

    print("\n  Ctrl+C 로 연결을 종료합니다.")
    try:
        while True:
            time.sleep(60)
    except KeyboardInterrupt:
        print("\n[slink] 연결을 종료합니다.")


def cmd_disconnect(args):
    running, pid = _is_daemon_running()
    if not running:
        print("[slink] 실행 중인 백그라운드 세션이 없습니다.")
        sys.exit(1)
    os.kill(pid, signal.SIGTERM)
    _remove_pid()
    print(f"[slink] 백그라운드 세션 종료 (PID: {pid})")


def cmd_status(args):
    cfg = load_config()
    relay_url = cfg["relay_url"].rstrip("/")
    email = cfg["email"]
    api_key = cfg["api_key"]

    try:
        resp = requests.get(
            f"{relay_url}/api/session/by-owner/{email}",
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=10,
        )
    except requests.ConnectionError:
        print("[slink] ✗ Relay 서버에 연결할 수 없습니다.")
        sys.exit(1)

    if resp.status_code == 404:
        print("[slink] ✗ 세션 없음 — Colab을 먼저 실행하세요.")
        sys.exit(1)

    resp.raise_for_status()
    session = resp.json()
    remaining = format_remaining(session.get("expiresAt", ""))

    running, pid = _is_daemon_running()
    daemon_info = f" | 백그라운드 PID: {pid}" if running else " | keepalive 없음 (slink connect -d 권장)"

    print(f"[slink] ✓ 연결됨 (만료까지 {remaining}){daemon_info}")
    print(f"  URL  : {session['ngrokHost']}")


# ── VM Agent (Service Portal 외부 공개) ──────────────────────────────────────

AGENT_STATE = os.path.expanduser("~/.slink-agent.json")
AGENT_POLL_INTERVAL = 10  # seconds


def _agent_save_state(state: dict):
    with open(AGENT_STATE, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)


def _start_cloudflared(local_port: int, timeout: int = 30):
    """cloudflared Quick Tunnel을 실행하고 trycloudflare.com URL을 반환합니다."""
    import subprocess
    import re
    import threading

    url_pattern = re.compile(r'https://[a-zA-Z0-9-]+\.trycloudflare\.com')
    found_url = [None]
    url_event = threading.Event()

    try:
        proc = subprocess.Popen(
            ["cloudflared", "tunnel", "--url", f"http://localhost:{local_port}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
    except FileNotFoundError:
        print("[slink-agent] 오류: cloudflared 가 설치되어 있지 않습니다.")
        print("[slink-agent]   설치 방법 (Linux amd64):")
        print("[slink-agent]     wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O /tmp/cloudflared")
        print("[slink-agent]     chmod +x /tmp/cloudflared && sudo mv /tmp/cloudflared /usr/local/bin/cloudflared")
        return None, None

    def _reader():
        for line in proc.stdout:
            m = url_pattern.search(line)
            if m:
                found_url[0] = m.group(0)
                url_event.set()
        url_event.set()  # signal even if URL not found (process ended)

    t = threading.Thread(target=_reader, daemon=True)
    t.start()
    url_event.wait(timeout=timeout)

    if found_url[0]:
        return proc, found_url[0]

    proc.terminate()
    return None, None


def _agent_report(relay_url: str, agent_id: str, agent_token: str,
                  service_id: str, event: str,
                  public_url: str = None, reason: str = None) -> bool:
    """Relay에 터널 이벤트를 보고한다. 성공 시 True, 실패 시 False를 반환한다."""
    body = {"serviceId": service_id, "event": event}
    if public_url:
        body["publicUrl"] = public_url
    if reason:
        body["reason"] = reason
    try:
        resp = requests.post(
            f"{relay_url}/api/agents/{agent_id}/report",
            headers={"X-Agent-Token": agent_token},
            json=body,
            timeout=10,
        )
        resp.raise_for_status()
        return True
    except Exception as e:
        status = getattr(getattr(e, "response", None), "status_code", None)
        if status:
            print(f"[slink-agent] 보고 실패 (serviceId={service_id}, event={event}, HTTP {status}): {e}")
        else:
            print(f"[slink-agent] 보고 실패 (serviceId={service_id}, event={event}): {e}")
        return False


def cmd_agent_start(args):
    cfg = load_config_optional()   # agent는 SOLID 인증 → slink init 불필요
    relay_url = (args.relay or cfg.get("relay_url", RELAY_DEFAULT)).rstrip("/")
    instance_id = args.instance_id

    if not instance_id:
        instance_id = cfg.get("instance_id", "")
    if not instance_id:
        print("[slink-agent] 오류: --instance-id 를 지정하거나 ~/.slinkrc에 instance_id를 저장하세요.")
        print("[slink-agent]   예: slink agent start --instance-id solid-32211690")
        sys.exit(1)

    print(f"[slink-agent] 인스턴스: {instance_id}")
    print(f"[slink-agent] Relay: {relay_url}")

    # SOLID 로그인으로 등록한다(등록만 SOLID 세션 slk-, 이후 heartbeat/report는 발급된 at- 토큰).
    username = args.username or cfg.get("student_id") or ""
    domain = args.domain if args.domain is not None else cfg.get("domain", "")
    if not username:
        username = input("  SOLID 학번/계정: ").strip()
    password = args.password or os.getenv("SLINK_SOLID_PASSWORD")
    if not password:
        password = getpass.getpass(f"  SOLID 비밀번호 ({username}): ")

    try:
        login_resp = requests.post(
            f"{relay_url}/api/auth/login",
            json={"username": username, "password": password, "domain": domain},
            timeout=10,
        )
    except requests.ConnectionError:
        print(f"[slink-agent] 오류: Relay 서버에 연결할 수 없습니다 ({relay_url})")
        sys.exit(1)
    if login_resp.status_code == 401:
        print("[slink-agent] 오류: SOLID 로그인 실패. 학번/비밀번호/도메인을 확인하세요.")
        sys.exit(1)
    login_resp.raise_for_status()
    solid_token = login_resp.json()["token"]

    # Register this agent (SOLID 세션이 학생 계정=학번에 에이전트를 묶는다)
    try:
        resp = requests.post(
            f"{relay_url}/api/agents/register",
            json={"instanceId": instance_id},
            headers={"Authorization": f"Bearer {solid_token}"},
            timeout=10,
        )
    except requests.ConnectionError:
        print(f"[slink-agent] 오류: Relay 서버에 연결할 수 없습니다 ({relay_url})")
        sys.exit(1)
    if resp.status_code == 401:
        print("[slink-agent] 오류: 에이전트 등록 인증 실패(SOLID 세션 만료/무효).")
        sys.exit(1)
    resp.raise_for_status()

    data = resp.json()
    agent_id = data["agentId"]
    agent_token = data["agentToken"]
    _agent_save_state({"agentId": agent_id, "agentToken": agent_token,
                       "instanceId": instance_id, "relayUrl": relay_url})

    if not shutil.which("cloudflared"):
        print()
        print("[slink-agent] 경고: cloudflared 가 PATH에 없습니다. OPEN_TUNNEL 명령 수신 시 터널을 열 수 없습니다.")
        print("[slink-agent]   설치 방법 (Linux amd64):")
        print("[slink-agent]     wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O /tmp/cloudflared")
        print("[slink-agent]     chmod +x /tmp/cloudflared && sudo mv /tmp/cloudflared /usr/local/bin/cloudflared")

    print()
    print("─" * 58)
    print(f"  Agent ID   {agent_id}")
    print(f"  인스턴스   {instance_id}")
    print(f"  Relay      {relay_url}")
    print(f"  Heartbeat  {AGENT_POLL_INTERVAL}초 간격  |  Ctrl+C 로 종료")
    print("─" * 58)
    print()

    # {serviceId: (cloudflared_process, public_url)}
    active_tunnels: dict = {}

    try:
        while True:
            try:
                hb_resp = requests.post(
                    f"{relay_url}/api/agents/{agent_id}/heartbeat",
                    headers={"X-Agent-Token": agent_token},
                    timeout=10,
                )
                if hb_resp.status_code == 401:
                    print("[slink-agent] 인증 실패. 재시작이 필요합니다.")
                    break
                hb_resp.raise_for_status()

                for cmd in hb_resp.json().get("commands", []):
                    svc_id    = cmd["serviceId"]
                    action    = cmd["action"]
                    local_port = cmd["localPort"]

                    if action == "OPEN_TUNNEL":
                        if svc_id in active_tunnels:
                            # Relay still shows PENDING — previous TUNNEL_READY report was lost.
                            # Re-deliver the existing URL; tunnel itself stays open.
                            _, existing_url = active_tunnels[svc_id]
                            _agent_report(relay_url, agent_id, agent_token,
                                          svc_id, "TUNNEL_READY", public_url=existing_url)
                            continue
                        print(f"[slink-agent] 터널 열기: {svc_id} → localhost:{local_port}")
                        proc, url = _start_cloudflared(local_port)
                        if url:
                            active_tunnels[svc_id] = (proc, url)
                            ok = _agent_report(relay_url, agent_id, agent_token,
                                               svc_id, "TUNNEL_READY", public_url=url)
                            if ok:
                                print(f"[slink-agent] 터널 열림: {url}")
                            else:
                                # Report failed. Tunnel remains in active_tunnels so the
                                # next heartbeat cycle re-sends TUNNEL_READY automatically.
                                print(f"[slink-agent] 경고: TUNNEL_READY 보고 실패, 다음 heartbeat에서 재시도 ({url})")
                        else:
                            print(f"[slink-agent] 오류: 터널 URL 획득 실패 (serviceId={svc_id})")
                            _agent_report(relay_url, agent_id, agent_token,
                                          svc_id, "TUNNEL_FAILED", reason="URL not obtained from cloudflared")

                    elif action == "CLOSE_TUNNEL":
                        if svc_id in active_tunnels:
                            proc, url = active_tunnels.pop(svc_id)
                            proc.terminate()
                            print(f"[slink-agent] 터널 종료: {svc_id}")
                        _agent_report(relay_url, agent_id, agent_token, svc_id, "TUNNEL_STOPPED")

            except KeyboardInterrupt:
                raise
            except Exception as e:
                print(f"[slink-agent] 오류: {e}")

            time.sleep(AGENT_POLL_INTERVAL)

    except KeyboardInterrupt:
        pass
    finally:
        print("\n[slink-agent] 종료 중 — 실행 중인 터널을 닫습니다...")
        for svc_id, (proc, url) in list(active_tunnels.items()):
            proc.terminate()
            _agent_report(relay_url, agent_id, agent_token, svc_id, "TUNNEL_STOPPED")
        print("[slink-agent] 종료됨.")


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        prog="slink",
        description="Solid-Link: SOLID VM <-> Colab GPU 연결 도구"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # init
    i = sub.add_parser("init", help="학번/이메일 등록 및 API Key 발급")
    i.add_argument(
        "--relay", default=os.getenv(RELAY_ENV, RELAY_DEFAULT), metavar="URL",
        help=f"Relay 서버 주소 (기본값: {RELAY_DEFAULT})"
    )

    # whoami
    sub.add_parser("whoami", help="현재 등록된 사용자 정보 확인")

    # connect
    c = sub.add_parser("connect", help="Colab GPU 세션에 연결합니다")
    c.add_argument("code", nargs="?", default=None, help="6자리 연결 코드 (생략 시 ~/.slinkrc 사용)")
    c.add_argument("-d", "--daemon", action="store_true", help="백그라운드로 실행 (터미널 점유 없음)")
    c.add_argument(
        "--sync-dir", default=".", metavar="DIR",
        help="Colab에 업로드할 로컬 디렉토리"
    )
    c.add_argument(
        "--relay", default=os.getenv(RELAY_ENV, RELAY_DEFAULT), metavar="URL",
        help=f"Relay 서버 주소 (환경변수 {RELAY_ENV} 또는 기본값: {RELAY_DEFAULT})"
    )
    c.add_argument(
        "--resource", default=None, metavar="ID",
        help="외부 자원 ID로 연결 (포털 '외부 자원 연결'). SOLID 로그인을 사용합니다."
    )
    c.add_argument("--username", default=None, metavar="ID",
                   help="SOLID 학번/계정 (--resource 사용 시; 생략 시 ~/.slinkrc 또는 프롬프트)")
    c.add_argument("--password", default=None, metavar="PW",
                   help="SOLID 비밀번호 (--resource 헤드리스용; 없으면 SLINK_SOLID_PASSWORD 또는 프롬프트)")
    c.add_argument("--domain", default=None, metavar="DOMAIN",
                   help="SOLID 도메인 (--resource 사용 시, 선택)")

    # disconnect
    sub.add_parser("disconnect", help="백그라운드 keepalive 세션 종료")

    # status
    sub.add_parser("status", help="현재 Colab 세션 상태 확인")

    # agent
    agent_p = sub.add_parser("agent", help="Service Portal VM Agent 관리")
    agent_sub = agent_p.add_subparsers(dest="agent_command", required=True)
    agent_start = agent_sub.add_parser("start", help="VM Agent 시작 (Service Portal 외부 공개)")
    agent_start.add_argument(
        "--instance-id", default=None, metavar="ID",
        help="이 VM의 인스턴스 ID (예: solid-32211690). 생략 시 ~/.slinkrc의 instance_id 사용."
    )
    agent_start.add_argument(
        "--relay", default=None, metavar="URL",
        help=f"Relay 서버 주소 (기본값: ~/.slinkrc의 relay_url 또는 {RELAY_DEFAULT})"
    )
    agent_start.add_argument(
        "--username", default=None, metavar="ID",
        help="SOLID 학번/계정 (생략 시 ~/.slinkrc의 student_id, 없으면 프롬프트)"
    )
    agent_start.add_argument(
        "--password", default=None, metavar="PW",
        help="SOLID 비밀번호 (생략 시 환경변수 SLINK_SOLID_PASSWORD 또는 프롬프트). 헤드리스/systemd용."
    )
    agent_start.add_argument(
        "--domain", default=None, metavar="DOMAIN",
        help="SOLID 도메인 (선택)"
    )

    args = parser.parse_args()

    if args.command == "agent":
        if args.agent_command == "start":
            cmd_agent_start(args)
        return

    {
        "init": cmd_init,
        "whoami": cmd_whoami,
        "connect": cmd_connect,
        "disconnect": cmd_disconnect,
        "status": cmd_status,
    }[args.command](args)


if __name__ == "__main__":
    main()
