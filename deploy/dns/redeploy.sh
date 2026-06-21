#!/usr/bin/env bash
# slink DNS 서버 재배포: 최신 코드 pull → jar 빌드 → 교체 → 재시작.
# 코드(자바) 변경 시 사용. 화면(정적 파일)만 바꿨으면 git pull + 새로고침이면 끝(재빌드 불필요).
#
# 사용: bash deploy/dns/redeploy.sh
set -euo pipefail

cd "$(dirname "$0")/../.."   # repo 루트로
echo "[1/5] git pull";        git pull
echo "[2/5] bootJar";         ./gradlew bootJar
echo "[3/5] jar 교체";        sudo cp build/libs/connectGPU-0.0.1-SNAPSHOT.jar /opt/slink/slink.jar
echo "[4/5] systemd 유닛 갱신"; sudo cp deploy/dns/slink-dns.service /etc/systemd/system/slink-dns.service
echo "[5/5] 재시작";          sudo systemctl daemon-reload && sudo systemctl restart slink-dns

sleep 3
if systemctl is-active --quiet slink-dns; then
  echo "✓ slink-dns 재기동 완료"
  journalctl -u slink-dns -n 5 --no-pager | grep -E "DNS-ZONE|CLOUDSTACK|Started" || true
else
  echo "✗ 기동 실패 — 로그:"; journalctl -u slink-dns -n 30 --no-pager
  exit 1
fi
