"""Export a trained policy for the gym's Java ``PolicySearch``.

Writes two files:

* ``<out>.json``: feature names, standardisation and the MLP's layers, read
  by ``net.wurstclient.gametest.gym.PolicyModel``.
* ``<out>.fixtures.jsonl``: test decisions as the gym logged them, each with
  the features and probabilities Python computes for it. Java's
  ``PolicyParityCheck`` recomputes both from the logged JSON and compares.

Usage (from ``analysis/``)::

    .venv/bin/python export_policy.py models/poc_full.pt \\
        ../spearmace-gym/policies/poc_full.json

Then in the gym: ``/gym start 200 a=policy:policies/poc_full.json b=rules``.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
import torch

import policy_data as pdm
import policy_model as pm
import spearmace_data as sd

DEFAULT_RUNS = ["20261105-002113", "20261105-075355"]


def export_model(ckpt: dict, out: Path) -> None:
    state = ckpt["state_dict"]
    linear = sorted({k.rsplit(".", 1)[0] for k in state if k.endswith(".weight")},
                    key=lambda k: int(k.split(".")[1]))
    layers = [{"weight": state[f"{k}.weight"].tolist(),
               "bias": state[f"{k}.bias"].tolist()} for k in linear]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "format": "spearmace-policy-mlp-v1",
        "feature_set": ckpt["feature_set"],
        "feature_names": ckpt["feature_names"],
        "mean": np.asarray(ckpt["mean"], dtype=float).tolist(),
        "std": np.asarray(ckpt["std"], dtype=float).tolist(),
        "activation": "relu",
        "layers": layers,
    }))


def _raw_lines(run_dir: Path, side: str, keys: set[int]) -> dict[int, str]:
    out = {}
    with open(run_dir / f"decisions-{side}.jsonl", "rb") as fh:
        for i, line in enumerate(fh):
            if i in keys:
                out[i] = line.decode().strip()
                if len(out) == len(keys):
                    break
    return out


def export_fixtures(ckpt_path: Path, out: Path, runs: list[Path],
                    n: int = 300, seed: int = 0) -> int:
    model, ckpt = pm.load(ckpt_path)
    d = pdm.build(runs, ckpt["feature_set"])
    if d.feature_names != ckpt["feature_names"]:
        raise ValueError("feature names changed since the model was trained")
    d.mean, d.std = np.asarray(ckpt["mean"]), np.asarray(ckpt["std"])

    test = d.split("test")
    rng = np.random.default_rng(seed)
    rows = np.sort(rng.choice(test, size=min(n, len(test)), replace=False))
    probs = pm.predict(model, d, rows)
    meta = d.meta.iloc[rows]

    lines = {}
    for (run, side), g in meta.groupby(["run", "side"]):
        got = _raw_lines(sd.GYM_DIR / run, side, set(g.key.tolist()))
        lines.update({(run, side, k): v for k, v in got.items()})

    with open(out, "w") as fh:
        for j, row in enumerate(rows):
            m = meta.iloc[j]
            k = int(d.mask[row].sum())
            fh.write(json.dumps({
                "decision": json.loads(lines[(m.run, m.side, int(m.key))]),
                "features": d.X[row, :k].astype(float).tolist(),
                "probs": probs[j, :k].astype(float).tolist(),
            }) + "\n")
    return len(rows)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("checkpoint", type=Path)
    ap.add_argument("out", type=Path, help="the model JSON to write")
    ap.add_argument("--runs", nargs="*", default=DEFAULT_RUNS,
                    help="gym runs to take fixture decisions from")
    ap.add_argument("--fixtures", type=int, default=300)
    args = ap.parse_args()

    ckpt = torch.load(args.checkpoint, weights_only=False)
    export_model(ckpt, args.out)
    print("model:", args.out)
    fixtures = args.out.with_suffix(".fixtures.jsonl")
    n = export_fixtures(args.checkpoint, fixtures,
                        [sd.GYM_DIR / r for r in args.runs], args.fixtures)
    print(f"fixtures: {fixtures} ({n} decisions)")


if __name__ == "__main__":
    main()
