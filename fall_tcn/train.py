"""1D-TCN 낙상 분류기 학습 + 평가 (vs 규칙 baseline).

- 시퀀스 단위 분할(GroupShuffleSplit)로 train/test 누수 방지.
- 클래스 불균형(낙상 윈도우가 소수) → 가중 CrossEntropy.
- 기존 규칙 방식과 같은 테스트셋에서 precision/recall/F1 비교표 출력.
"""
from pathlib import Path
import argparse

import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import DataLoader
from sklearn.model_selection import GroupShuffleSplit
from sklearn.metrics import precision_recall_fscore_support, confusion_matrix

from dataset import build_windows, normalize, WindowDataset
from features import NUM_FEATURES, FEATURE_NAMES
from model import TCN

BASE = Path(__file__).resolve().parent


def rule_baseline(X_raw: np.ndarray) -> np.ndarray:
    """기존 임계값 방식 모사: 윈도우 내 어느 프레임이 '수평 + 낮음'이면 낙상."""
    com_y = X_raw[:, :, FEATURE_NAMES.index("com_y")]
    torso = X_raw[:, :, FEATURE_NAMES.index("torso_angle")]  # 0~1 (1=수평)
    horizontal_low = (torso >= 45.0 / 90.0) & (com_y >= 0.5)
    return horizontal_low.any(axis=1).astype(np.int64)


def report(name: str, y_true, y_pred):
    p, r, f1, _ = precision_recall_fscore_support(
        y_true, y_pred, average="binary", zero_division=0
    )
    cm = confusion_matrix(y_true, y_pred, labels=[0, 1])
    print(f"\n[{name}]  precision={p:.3f}  recall={r:.3f}  f1={f1:.3f}")
    print(f"  confusion [[TN FP],[FN TP]] = {cm.tolist()}")
    return p, r, f1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--landmarks", default=str(BASE / "landmarks"))
    ap.add_argument("--falls_csv", default=str(BASE / "urfd" / "urfall-cam0-falls.csv"))
    ap.add_argument("--adls_csv", default=str(BASE / "urfd" / "urfall-cam0-adls.csv"))
    ap.add_argument("--window", type=int, default=10, help="윈도우 프레임 수 (10fps에서 10=1초)")
    ap.add_argument("--stride", type=int, default=5)
    ap.add_argument("--frame_stride", type=int, default=3, help="30fps→배포fps 다운샘플 (3=10fps)")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch", type=int, default=64)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--test_size", type=float, default=0.25)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default=str(BASE / "tcn_fall.pt"))
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)

    print("윈도우 생성 중...")
    X, y, groups = build_windows(
        Path(args.landmarks), Path(args.falls_csv), Path(args.adls_csv),
        window=args.window, stride=args.stride, frame_stride=args.frame_stride,
    )
    eff_fps = 30 // max(args.frame_stride, 1)
    print(f"  윈도우 {len(y)}개, 낙상 {int(y.sum())} / 정상 {int((y == 0).sum())}, "
          f"피처 {NUM_FEATURES}, ~{eff_fps}fps, 윈도우={args.window}프레임({args.window / eff_fps:.1f}초)")

    # 시퀀스 단위 분할
    gss = GroupShuffleSplit(n_splits=1, test_size=args.test_size, random_state=args.seed)
    train_idx, test_idx = next(gss.split(X, y, groups))
    X_tr_raw, X_te_raw = X[train_idx], X[test_idx]
    y_tr, y_te = y[train_idx], y[test_idx]

    # 정규화 (train 통계로 test 적용)
    X_tr, mean, std = normalize(X_tr_raw)
    X_te, _, _ = normalize(X_te_raw, mean, std)

    tr_loader = DataLoader(WindowDataset(X_tr, y_tr), batch_size=args.batch, shuffle=True)
    te_loader = DataLoader(WindowDataset(X_te, y_te), batch_size=args.batch)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = TCN(num_features=NUM_FEATURES).to(device)
    print(f"  모델 파라미터 {model.num_params():,}개, device={device}")

    # 클래스 가중치
    counts = np.bincount(y_tr, minlength=2).astype(np.float32)
    weights = torch.tensor(counts.sum() / (2 * counts + 1e-6), device=device)
    criterion = nn.CrossEntropyLoss(weight=weights)
    optim = torch.optim.Adam(model.parameters(), lr=args.lr)

    for epoch in range(1, args.epochs + 1):
        model.train()
        total = 0.0
        for xb, yb in tr_loader:
            xb, yb = xb.to(device), yb.to(device)
            optim.zero_grad()
            loss = criterion(model(xb), yb)
            loss.backward()
            optim.step()
            total += loss.item() * len(yb)
        if epoch % 5 == 0 or epoch == 1:
            print(f"  epoch {epoch:3d}  loss={total / len(y_tr):.4f}")

    # 평가
    model.eval()
    preds = []
    with torch.no_grad():
        for xb, _ in te_loader:
            preds.append(model(xb.to(device)).argmax(1).cpu().numpy())
    y_pred_tcn = np.concatenate(preds)
    y_pred_rule = rule_baseline(X_te_raw)

    print("\n================ 테스트셋 비교 ================")
    report("규칙 baseline", y_te, y_pred_rule)
    report("1D-TCN (제안)", y_te, y_pred_tcn)

    torch.save(
        {"model": model.state_dict(), "mean": mean, "std": std,
         "window": args.window, "num_features": NUM_FEATURES},
        args.out,
    )
    print(f"\n모델 저장 -> {args.out}")


if __name__ == "__main__":
    main()
