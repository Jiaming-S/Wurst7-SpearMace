"""Training data for a candidate-scoring policy that imitates the expert.

Every logged decision offers 1-10 candidate actions. The expert (beam 8x5)
scores each of them (``expert_best``) and picks the best. This module turns
the decision logs into padded arrays:

* ``X``      ``[decisions, MAX_CANDIDATES, features]`` per-candidate features
             (the decision's state, repeated, plus the candidate's own)
* ``mask``   ``[decisions, MAX_CANDIDATES]`` real candidates
* ``values`` ``[decisions, MAX_CANDIDATES]`` the expert's value per candidate
* ``label``  ``[decisions]`` the expert's pick

and a ``meta`` frame with one row per decision (split, search type, ...).

Two feature sets:

* ``"full"``: state, action parameters and the simulator's predicted outcome
  for the candidate. Only valid where every candidate was simulated: the
  Rules agent stops at the first candidate that works, so a missing outcome
  gives its pick away. Those decisions are left out.
* ``"no_outcome"``: state and action parameters only, on every decision.

Nothing derived from ``acted_*`` or ``expert_*`` (or the candidate's
position in the list) is used as a feature.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd

import spearmace_data as sd

MAX_CANDIDATES = 10
GROUND_Y = -60.0

TYPES = ["SpearLunge", "Hold", "Drift", "EscapeLunge", "PearlCatch",
         "WindChargeJump", "Eat", "MaceStrike", "Cushion", "Escape"]
PRE_ITEMS = ["HAND", "SPEAR", "SWORD", "AXE"]
EVENTS = ["IMPULSE", "THREW_WIND_CHARGE", "LANDED", "JABBED", "APEX",
          "STOPPED_EATING", "STARTED_EATING", "THREW_PEARL", "TELEPORTED",
          "EXPLODED", "ATE", "STRUCK", "PEARL_LANDED"]
FEATURE_SETS = ("full", "no_outcome")
SPLITS = ("train", "val", "test")


@dataclass
class PolicyData:
    feature_set: str
    feature_names: list[str]
    X: np.ndarray
    mask: np.ndarray
    values: np.ndarray
    label: np.ndarray
    meta: pd.DataFrame
    # per-feature standardisation, fitted on the train split
    mean: np.ndarray = field(default=None)
    std: np.ndarray = field(default=None)

    def split(self, name: str) -> np.ndarray:
        return np.flatnonzero(self.meta.split.to_numpy() == name)

    def soft_targets(self, temperature: float, alpha: float = 1.0) -> np.ndarray:
        """``alpha * softmax(expert value / T) + (1 - alpha) * onehot(pick)``.

        Candidates without an expert value (infeasible in the expert's
        simulation) get no probability from the softmax part. The value is
        the best rank anywhere below the candidate, while the expert picks
        the top of its deepest beam level, so the two disagree on about 11%
        of decisions; ``alpha < 1`` keeps the actual pick in the target.
        """
        valid = self.mask & ~np.isnan(self.values)
        v = np.where(valid, self.values / temperature, -np.inf)
        v = v - v.max(axis=1, keepdims=True)
        e = np.where(valid, np.exp(v), 0.0)
        soft = e / e.sum(axis=1, keepdims=True)
        hard = np.zeros_like(soft)
        hard[np.arange(len(self.label)), self.label] = 1.0
        return (alpha * soft + (1 - alpha) * hard).astype(np.float32)


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------

def _split_of(key: str) -> str:
    """80/10/10 by a stable hash, so a side-episode never crosses splits."""
    h = int(hashlib.md5(key.encode()).hexdigest(), 16) % 10
    return "train" if h < 8 else "val" if h == 8 else "test"


def _bearing(dx: np.ndarray, dz: np.ndarray) -> np.ndarray:
    """Minecraft yaw (degrees) that looks along (dx, dz)."""
    return np.degrees(np.arctan2(dz, dx)) - 90.0


def _angle_features(prefix: str, yaw: pd.Series, bearing: pd.Series) -> dict:
    rel = np.radians(yaw.astype(float) - bearing)
    has = yaw.notna()
    return {f"{prefix}_sin": np.where(has, np.sin(rel), 0.0),
            f"{prefix}_cos": np.where(has, np.cos(rel), 0.0),
            f"has_{prefix}": has.astype(float)}


def _bool(s: pd.Series) -> np.ndarray:
    return s.fillna(False).astype(float).to_numpy()


def _num(s: pd.Series) -> np.ndarray:
    return pd.to_numeric(s, errors="coerce").fillna(0.0).astype(float).to_numpy()


# --------------------------------------------------------------------------
# features
# --------------------------------------------------------------------------

STATE_COLS_NUM = ["self_vel_x", "self_vel_y", "self_vel_z", "tgt_vel_x",
                  "tgt_vel_y", "tgt_vel_z", "self_fallDistance",
                  "self_health", "self_absorption", "tgt_health",
                  "tgt_absorption", "kit_wind_charge", "kit_ender_pearl",
                  "kit_golden_apple", "self_attackStrength", "tgt_hurtTime",
                  "self_useTicks"]
STATE_COLS_BOOL = ["self_onGround", "tgt_onGround", "tgt_shieldRaised",
                   "self_sprinting"]


def _state_features(dec: pd.DataFrame) -> pd.DataFrame:
    dx = dec.tgt_pos_x - dec.self_pos_x
    dy = dec.tgt_pos_y - dec.self_pos_y
    dz = dec.tgt_pos_z - dec.self_pos_z
    f = {"s_dx": dx, "s_dy": dy, "s_dz": dz,
         "s_hdist": dec.hdist, "s_dist": dec.dist,
         "s_height": dec.self_pos_y - GROUND_Y,
         "s_tgt_height": dec.tgt_pos_y - GROUND_Y}
    for c in STATE_COLS_NUM:
        f["s_" + c] = _num(dec[c])
    for c in STATE_COLS_BOOL:
        f["s_" + c] = _bool(dec[c])
    out = pd.DataFrame(f, index=dec.index).fillna(0.0)
    out["_bearing"] = _bearing(dx, dz).fillna(0.0)
    return out


def _candidate_features(c: pd.DataFrame, bearing: pd.Series,
                        dec_pos: pd.DataFrame, outcome: bool) -> pd.DataFrame:
    f = {}
    for t in TYPES:
        f[f"c_type_{t}"] = (c.type == t).astype(float).to_numpy()
    f.update(_angle_features("c_yaw", c.p_yaw, bearing))
    f.update(_angle_features("c_away", c.p_awayYaw, bearing))
    f["c_pitch"] = _num(c.p_pitch) / 90.0
    f["c_delay"] = _num(c.p_delay)
    f["c_minChance"] = _num(c.p_minChance)
    f["c_eat"] = _bool(c.p_eat)
    f["c_jumpFirst"] = _bool(c.p_jumpFirst)
    f["c_forward"] = _bool(c.p_forward)
    f["c_ticks_param"] = _num(c.p_ticks)
    for p in PRE_ITEMS:
        f[f"c_pre_{p}"] = (c.p_pre == p).astype(float).to_numpy()
    for w in ("on the ground", "falling", "rising"):
        f[f"c_why_{w.replace(' ', '_')}"] = (c.p_why == w).astype(float).to_numpy()

    if outcome:
        f["o_ticks"] = _num(c.ticks)
        f["o_dealt"] = _num(c.dealt)
        f["o_taken"] = _num(c.taken)
        f["o_chance"] = _num(c.chance)
        f["o_failed"] = c.failure.notna().astype(float).to_numpy()
        ev = c.events.fillna("")
        for e in EVENTS:
            f[f"o_ev_{e}"] = ev.str.contains(rf"(?:^|\|){e}(?:$|\|)").astype(float).to_numpy()
        ex = c.end_x.astype(float) - dec_pos.self_pos_x.to_numpy()
        ey = c.end_y.astype(float) - dec_pos.self_pos_y.to_numpy()
        ez = c.end_z.astype(float) - dec_pos.self_pos_z.to_numpy()
        tx = dec_pos.tgt_pos_x.to_numpy() - c.end_x.astype(float)
        tz = dec_pos.tgt_pos_z.to_numpy() - c.end_z.astype(float)
        f["o_end_dx"] = ex.fillna(0).to_numpy()
        f["o_end_dy"] = ey.fillna(0).to_numpy()
        f["o_end_dz"] = ez.fillna(0).to_numpy()
        f["o_end_height"] = (c.end_y.astype(float) - GROUND_Y).fillna(0).to_numpy()
        f["o_end_hdist_tgt"] = np.hypot(tx, tz).fillna(0).to_numpy()
        f["o_end_dy_tgt"] = (dec_pos.tgt_pos_y.to_numpy() - c.end_y.astype(float)).fillna(0).to_numpy()
    return pd.DataFrame(f, index=c.index)


# --------------------------------------------------------------------------
# build
# --------------------------------------------------------------------------

def build(runs: Iterable[Path | str], feature_set: str = "full",
          data: dict[str, pd.DataFrame] | None = None) -> PolicyData:
    """Build the padded arrays for ``runs``.

    ``data`` can pass an already loaded ``sd.load_runs(..., with_candidates=True)``
    result to avoid loading twice.
    """
    if feature_set not in FEATURE_SETS:
        raise ValueError(feature_set)
    if data is None:
        data = sd.load_runs(runs, with_candidates=True)
    dec = sd.attach_episode_info(data["decisions"], data["sides"])
    cand = data["candidates"]
    keys = ["run", "side", "key"]

    # decisions the expert labelled with a real choice
    dec = dec[dec.has_expert & (dec.n_candidates >= 2)
              & (dec.n_candidates <= MAX_CANDIDATES) & dec.expert_idx.notna()]
    cand = cand.merge(dec[keys], on=keys)
    # a candidate without an expert value was infeasible in the expert's
    # simulation; it stays in the set with no target probability
    if feature_set == "full":
        ok = cand.groupby(keys).has_outcome.all()
        ok = ok[ok].index.to_frame(index=False)
        dec = dec.merge(ok, on=keys)
        cand = cand.merge(ok, on=keys)

    dec = dec.sort_values(keys).reset_index(drop=True)
    dec["row"] = np.arange(len(dec))
    cand = cand.merge(dec[keys + ["row"]], on=keys).sort_values(["row", "idx"])
    cand = cand.reset_index(drop=True)

    state = _state_features(dec)
    per_cand_state = state.iloc[cand.row.to_numpy()].reset_index(drop=True)
    dec_pos = dec[["self_pos_x", "self_pos_y", "self_pos_z", "tgt_pos_x",
                   "tgt_pos_y", "tgt_pos_z"]].iloc[cand.row.to_numpy()]
    cfeat = _candidate_features(cand, per_cand_state["_bearing"].to_numpy(),
                                dec_pos.reset_index(drop=True),
                                outcome=feature_set == "full")
    feats = pd.concat([per_cand_state.drop(columns="_bearing"), cfeat], axis=1)
    names = list(feats.columns)
    assert not any(n.startswith(("acted", "expert")) for n in names)

    n, k = len(dec), MAX_CANDIDATES
    X = np.zeros((n, k, len(names)), dtype=np.float32)
    mask = np.zeros((n, k), dtype=bool)
    values = np.full((n, k), np.nan, dtype=np.float32)
    r, i = cand.row.to_numpy(), cand.idx.to_numpy()
    X[r, i] = feats.to_numpy(dtype=np.float32)
    mask[r, i] = True
    values[r, i] = cand.expert_best.to_numpy(dtype=np.float32)

    # greedy baseline: best predicted (dealt - taken); first one on ties
    score = (cand.dealt.fillna(0) - cand.taken.fillna(0)).to_numpy()
    greedy = pd.Series(score).groupby(r).idxmax()
    greedy_idx = i[greedy.to_numpy()]

    ep_key = (dec.run + "/" + dec.pair.astype(str) + "/"
              + dec.episode.astype(str) + "/" + dec.role)
    meta = pd.DataFrame({
        "run": dec.run, "side": dec.side, "key": dec.key, "episode_key": ep_key,
        "split": ep_key.map(_split_of),
        "search": dec.search, "label_type": dec.expert_type,
        "acted_idx": dec.acted_idx.astype(int), "explored": dec.acted_explored.fillna(False),
        "n_candidates": dec.n_candidates, "greedy_idx": greedy_idx,
        # the stale onGround bug: on the ground per the flag, but in the air
        "stale_ground": dec.self_onGround.fillna(False)
                        & (dec.self_pos_y - GROUND_Y > 0.5),
    })
    data_ = PolicyData(feature_set, names, X, mask, values,
                       dec.expert_idx.astype(int).to_numpy(), meta)
    _fit_standardisation(data_)
    return data_


def _fit_standardisation(d: PolicyData) -> None:
    """Mean/std of each feature over real train candidates; binary features
    (only 0/1 values) are left as they are."""
    tr = d.split("train")
    rows = d.X[tr][d.mask[tr]]
    mean = rows.mean(axis=0)
    std = rows.std(axis=0)
    binary = np.all((rows == 0) | (rows == 1), axis=0)
    mean[binary] = 0.0
    std[binary] = 1.0
    std[std < 1e-6] = 1.0
    d.mean, d.std = mean.astype(np.float32), std.astype(np.float32)


def standardised(d: PolicyData, idx: np.ndarray) -> np.ndarray:
    """``X[idx]`` standardised, with padded slots zeroed."""
    x = (d.X[idx] - d.mean) / d.std
    x[~d.mask[idx]] = 0.0
    return x
