"""A candidate scorer: one shared MLP scores every candidate of a decision,
and a softmax over the decision's candidates gives the policy.

Trained with cross-entropy against the targets from
:meth:`policy_data.PolicyData.soft_targets`.
"""

from __future__ import annotations

import copy
import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch
from torch import nn

from policy_data import PolicyData, standardised

MODELS_DIR = Path(__file__).resolve().parent / "models"


class CandidateScorer(nn.Module):
    def __init__(self, n_features: int, hidden: int = 128, dropout: float = 0.1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(n_features, hidden), nn.ReLU(), nn.Dropout(dropout),
            nn.Linear(hidden, hidden), nn.ReLU(), nn.Dropout(dropout),
            nn.Linear(hidden, 1),
        )

    def forward(self, x: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
        """Log-probabilities ``[batch, candidates]``; padded slots are -inf."""
        logits = self.net(x).squeeze(-1)
        logits = logits.masked_fill(~mask, float("-inf"))
        return torch.log_softmax(logits, dim=-1)


@dataclass
class TrainConfig:
    temperature: float = 3.0
    alpha: float = 0.5
    hidden: int = 128
    dropout: float = 0.1
    lr: float = 1e-3
    weight_decay: float = 1e-4
    batch_size: int = 1024
    max_epochs: int = 60
    patience: int = 6
    seed: int = 0


@dataclass
class TrainResult:
    model: CandidateScorer
    config: TrainConfig
    history: list[dict]
    best_epoch: int


def _tensors(d: PolicyData, idx: np.ndarray, targets: np.ndarray):
    return (torch.from_numpy(standardised(d, idx)),
            torch.from_numpy(d.mask[idx]),
            torch.from_numpy(targets[idx]))


def _loss(logp: torch.Tensor, target: torch.Tensor, mask: torch.Tensor):
    # padded slots have target 0; keep -inf * 0 out of the sum
    return -(target * logp.masked_fill(~mask, 0.0)).sum(-1).mean()


def train(d: PolicyData, config: TrainConfig | None = None,
          verbose: bool = True) -> TrainResult:
    cfg = config or TrainConfig()
    torch.manual_seed(cfg.seed)
    rng = np.random.default_rng(cfg.seed)
    targets = d.soft_targets(cfg.temperature, cfg.alpha)
    tr, va = d.split("train"), d.split("val")
    xt, mt, yt = _tensors(d, tr, targets)
    xv, mv, yv = _tensors(d, va, targets)
    label_v = torch.from_numpy(d.label[va])

    model = CandidateScorer(d.X.shape[-1], cfg.hidden, cfg.dropout)
    opt = torch.optim.AdamW(model.parameters(), lr=cfg.lr,
                            weight_decay=cfg.weight_decay)
    history, best, best_state, best_epoch, bad = [], np.inf, None, 0, 0
    for epoch in range(cfg.max_epochs):
        t0 = time.time()
        model.train()
        order = torch.from_numpy(rng.permutation(len(tr)))
        total = 0.0
        for s in range(0, len(tr), cfg.batch_size):
            b = order[s:s + cfg.batch_size]
            loss = _loss(model(xt[b], mt[b]), yt[b], mt[b])
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(b)
        model.eval()
        with torch.no_grad():
            lv = model(xv, mv)
            val_loss = _loss(lv, yv, mv).item()
            val_top1 = (lv.argmax(-1) == label_v).float().mean().item()
        history.append({"epoch": epoch, "train_loss": total / len(tr),
                        "val_loss": val_loss, "val_top1": val_top1,
                        "seconds": time.time() - t0})
        if verbose:
            print(f"epoch {epoch:2d}  train {total / len(tr):.4f}  "
                  f"val {val_loss:.4f}  val top-1 {val_top1:.3f}")
        if val_loss < best - 1e-4:
            best, best_state, best_epoch, bad = val_loss, copy.deepcopy(model.state_dict()), epoch, 0
        else:
            bad += 1
            if bad >= cfg.patience:
                break
    model.load_state_dict(best_state)
    model.eval()
    return TrainResult(model, cfg, history, best_epoch)


@torch.no_grad()
def predict(model: CandidateScorer, d: PolicyData, idx: np.ndarray,
            batch: int = 8192) -> np.ndarray:
    """Probabilities ``[len(idx), candidates]``."""
    out = []
    for s in range(0, len(idx), batch):
        b = idx[s:s + batch]
        x = torch.from_numpy(standardised(d, b))
        m = torch.from_numpy(d.mask[b])
        out.append(model(x, m).exp().numpy())
    return np.concatenate(out) if out else np.zeros((0, d.mask.shape[1]))


def save(result: TrainResult, d: PolicyData, name: str) -> Path:
    MODELS_DIR.mkdir(exist_ok=True)
    path = MODELS_DIR / f"{name}.pt"
    torch.save({
        "state_dict": result.model.state_dict(),
        "config": asdict(result.config),
        "feature_set": d.feature_set,
        "feature_names": d.feature_names,
        "mean": d.mean, "std": d.std,
        "history": result.history,
    }, path)
    (MODELS_DIR / f"{name}.json").write_text(json.dumps(
        {"config": asdict(result.config), "feature_set": d.feature_set,
         "best_epoch": result.best_epoch}, indent=2))
    return path


def load(path: Path | str) -> tuple[CandidateScorer, dict]:
    ckpt = torch.load(path, weights_only=False)
    cfg = ckpt["config"]
    model = CandidateScorer(len(ckpt["feature_names"]), cfg["hidden"], cfg["dropout"])
    model.load_state_dict(ckpt["state_dict"])
    model.eval()
    return model, ckpt
