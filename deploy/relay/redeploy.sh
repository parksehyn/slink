#!/usr/bin/env bash
# slink Relay 재배포: 최신 코드 pull → jar 빌드 → 교체 → 재시작.
# 코드(자바) 변경 시 사용. 화면(정적 파일)만 바꿨으면 git pull + 새로고침이면 끝(재빌드 불필요).
#
# 사용: bash deploy/relay/redeploy.sh
set -euo pipefail

cd "$(dirname "$0")/../.."   # repo 루트로
echo "[1/5] git pull";        git pull
echo "[2/5] bootJar";         ./gradlew bootJar
echo "[3/5] jar 교체";        sudo cp build/libs/connectGPU-0.0.1-SNAPSHOT.jar /opt/slink/slink.jar
echo "[4/5] systemd 유닛 갱신"; sudo cp deploy/relay/slink-relay.service /etc/systemd/system/slink-relay.service
echo "[5/5] 재시작";          sudo systemctl daemon-reload && sudo systemctl restart slink-relay

sleep 3
if systemctl is-active --quiet slink-relay; then
  echo "✓ slink-relay 재기동 완료"
  journalctl -u slink-relay -n 8 --no-pager | grep -E "SVC|CONN|AGENT|CLOUDSTACK|Started" || true
else
  echo "✗ 기동 실패 — 로그:"; journalctl -u slink-relay -n 30 --no-pager
  exit 1
fi
