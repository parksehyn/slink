#!/usr/bin/env python3
"""slink - SOLID VM <-> Colab GPU 연결 CLI"""

import argparse
import os
import sys

import requests

RELAY_ENV = "SLINK_RELAY"
RELAY_DEFAULT = "http://localhost:8081"


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
    """Jupyter REST API로 로컬 파일을 Colab에 업로드합니다."""
    import os

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


def cmd_connect(args):
    code = args.code
    relay_url = args.relay.rstrip("/")

    print(f"[slink] 코드 {code} 조회 중...")
    session = fetch_session(code, relay_url)

    jupyter_base_url = session["ngrokHost"].rstrip("/")
    token = session["jupyterToken"]
    lab_url = f"{jupyter_base_url}/lab?token={token}"

    # 파일 동기화 (--sync-dir 지정 시)
    sync_dir = os.path.abspath(args.sync_dir)
    if args.sync_dir != ".":
        print(f"[slink] 파일 동기화 중: {sync_dir} -> Colab")
        sync_files_http(jupyter_base_url, token, sync_dir)
    else:
        print("[slink] 팁: --sync-dir <경로> 로 파일을 Colab에 업로드할 수 있습니다.")

    print()
    print("=" * 62)
    print("  [slink] 연결 준비 완료!")
    print()
    print(f"  Jupyter 접속 주소:")
    print(f"  {lab_url}")
    print()
    print("  VS Code 연결 방법:")
    print("  1. .ipynb 파일 열기")
    print("  2. 오른쪽 상단 커널 선택 → 기존 Jupyter 서버")
    print(f"  3. URL: {jupyter_base_url}")
    print(f"  4. Token: {token}")
    print("=" * 62)


def main():
    parser = argparse.ArgumentParser(
        prog="slink",
        description="Solid-Link: SOLID VM <-> Colab GPU 연결 도구"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    c = sub.add_parser("connect", help="Colab GPU 세션에 연결합니다")
    c.add_argument("code", help="6자리 연결 코드 (Colab 노트북에서 확인)")
    c.add_argument(
        "--sync-dir", default=".", metavar="DIR",
        help="Colab에 업로드할 로컬 디렉토리 (기본값: 현재 디렉토리, 생략 시 동기화 안 함)"
    )
    c.add_argument(
        "--relay", default=os.getenv(RELAY_ENV, RELAY_DEFAULT), metavar="URL",
        help=f"Relay 서버 주소 (환경변수 {RELAY_ENV} 또는 기본값: {RELAY_DEFAULT})"
    )

    args = parser.parse_args()
    if args.command == "connect":
        cmd_connect(args)


if __name__ == "__main__":
    main()
