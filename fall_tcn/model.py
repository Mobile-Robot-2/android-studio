"""경량 1D-TCN (Temporal Convolutional Network) 낙상 분류기.

입력: (B, C=피처수, T=윈도우길이)  출력: (B, 2) 로짓.
팽창(dilated) 인과 합성곱 잔차블록 스택 → 시간축 평균풀링 → 선형.
파라미터 수만 개 수준으로 CPU 실시간·TFLite 변환이 쉽다.
"""
import torch
import torch.nn as nn


class Chomp1d(nn.Module):
    """인과성 유지를 위해 패딩으로 늘어난 오른쪽 꼬리를 잘라낸다."""

    def __init__(self, chomp: int):
        super().__init__()
        self.chomp = chomp

    def forward(self, x):
        return x[:, :, : -self.chomp].contiguous() if self.chomp > 0 else x


class TemporalBlock(nn.Module):
    def __init__(self, in_ch, out_ch, kernel, dilation, dropout=0.2):
        super().__init__()
        pad = (kernel - 1) * dilation
        self.net = nn.Sequential(
            nn.Conv1d(in_ch, out_ch, kernel, padding=pad, dilation=dilation),
            Chomp1d(pad),
            nn.BatchNorm1d(out_ch),
            nn.ReLU(),
            nn.Dropout(dropout),
            nn.Conv1d(out_ch, out_ch, kernel, padding=pad, dilation=dilation),
            Chomp1d(pad),
            nn.BatchNorm1d(out_ch),
            nn.ReLU(),
            nn.Dropout(dropout),
        )
        self.downsample = nn.Conv1d(in_ch, out_ch, 1) if in_ch != out_ch else None
        self.relu = nn.ReLU()

    def forward(self, x):
        out = self.net(x)
        res = x if self.downsample is None else self.downsample(x)
        return self.relu(out + res)


class TCN(nn.Module):
    def __init__(self, num_features: int, num_classes: int = 2,
                 channels=(32, 32, 64), kernel: int = 3, dropout: float = 0.2):
        super().__init__()
        layers = []
        in_ch = num_features
        for i, out_ch in enumerate(channels):
            layers.append(TemporalBlock(in_ch, out_ch, kernel, dilation=2 ** i, dropout=dropout))
            in_ch = out_ch
        self.tcn = nn.Sequential(*layers)
        self.pool = nn.AdaptiveAvgPool1d(1)
        self.head = nn.Linear(in_ch, num_classes)

    def forward(self, x):  # x: (B, C, T)
        h = self.tcn(x)
        h = self.pool(h).squeeze(-1)
        return self.head(h)

    def num_params(self) -> int:
        return sum(p.numel() for p in self.parameters())
