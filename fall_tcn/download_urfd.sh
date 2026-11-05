#!/usr/bin/env bash
# URFD(UR Fall Detection) cam0 MP4 + 프레임 라벨 CSV 다운로드.
# cam0 = 바닥과 수평(~1m) 뷰 (Temi 시점과 유사). 전체 수 GB.
set -u

BASE="https://fenix.ur.edu.pl/~mkepski/ds/data"
OUT="${1:-urfd}"
mkdir -p "$OUT"

echo "== 라벨 CSV =="
for f in urfall-cam0-falls.csv urfall-cam0-adls.csv; do
  wget -c -q --show-progress -P "$OUT" "$BASE/$f" || echo "  skip $f"
done

echo "== 낙상 cam0 영상 (fall-01..30) =="
for i in $(seq -w 1 30); do
  wget -c -q --show-progress -P "$OUT" "$BASE/fall-$i-cam0.mp4" || echo "  skip fall-$i"
done

echo "== 일상(ADL) cam0 영상 (adl-01..40) =="
for i in $(seq -w 1 40); do
  wget -c -q --show-progress -P "$OUT" "$BASE/adl-$i-cam0.mp4" || echo "  skip adl-$i"
done

echo "완료 -> $OUT ($(ls -1 "$OUT"/*.mp4 2>/dev/null | wc -l) 개 영상)"
