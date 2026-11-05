# Fall Detection TCN (pose-game-lsm1)

MediaPipe 스켈레톤 시퀀스 + 1D-TCN 기반 낙상감지. 기존 규칙(`fall_detector.py`)은 비교 baseline으로 유지.

## 파이프라인

```
URFD MP4  ──download_urfd.sh──▶  urfd/*.mp4 + 라벨 CSV
   │
   ├─extract_landmarks.py──▶  landmarks/<seq>.npz   (MediaPipe 33랜드마크 시퀀스)
   │
   ├─features.py (import)──▶   프레임별 피처 벡터 (COM y·속도, 몸통각도, visibility)
   │
   ├─dataset.py──────────▶     윈도우 [T,F] + 라벨 (URFD CSV 기반)
   │
   └─train.py───────────▶      1D-TCN 학습·평가 (vs 규칙 baseline)
```

## 실행 순서 (WSL/리눅스, 저장소 루트 기준)

```bash
cd fall_tcn
bash download_urfd.sh                 # 1) URFD MP4 + 라벨 내려받기 (~수 GB)
pip install -r requirements.txt       # 2) 의존성
python extract_landmarks.py           # 3) MediaPipe로 랜드마크 추출 → landmarks/
python train.py                       # 4) TCN 학습 + 평가
```

## 데이터

- **URFD (UR Fall Detection Dataset)** — cam0(바닥 수평, Temi 시점 유사). 낙상 30 + 일상(ADL) 40 시퀀스.
- 라이선스: CC BY-NC-SA 4.0, 비영리 학술용. 인용: Kwolek & Kepski, CMPB 117(3), 2014.
- 추후 AI Hub 71641(한국 노인 도메인)을 같은 파이프라인으로 추가 예정.

## 라벨 규약

URFD 프레임 라벨: `-1`=서있음, `0`=전이(낙하 중), `1`=바닥에 누움.
윈도우 라벨: 윈도우의 마지막 프레임 라벨이 `1`이면 낙상(1), 아니면 정상(0). ADL 시퀀스는 전부 정상.
(전이 프레임 `0`은 모호해 기본 제외 — dataset.py 옵션으로 조정 가능.)
