"""Loaders for SpearMace self-play gym runs.

A run directory (``spearmace-gym/<YYYYMMDD-HHMMSS>/``) holds:

* ``episodes.jsonl``       one line per finished episode (both sides' stats)
* ``decisions-<pair><side>.jsonl``  per-client stream of ``decision``,
  ``action_end`` and ``stop`` records
* ``client-Gym-*.log``     raw client logs (not parsed here)

Everything is flattened into pandas DataFrames. Parsing the decision streams
is the slow part (~400 MB of JSON per run), so each parsed table is cached as
parquet under ``analysis/.cache/`` and rebuilt only when the source file's
size or mtime changes.
"""

from __future__ import annotations

import os
import re
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import orjson
import pandas as pd

ANALYSIS_DIR = Path(__file__).resolve().parent
REPO_DIR = ANALYSIS_DIR.parent
GYM_DIR = REPO_DIR / "spearmace-gym"
CACHE_DIR = ANALYSIS_DIR / ".cache"
CACHE_VERSION = 2

_RUN_RE = re.compile(r"^\d{8}-\d{6}$")
_DECISION_FILE_RE = re.compile(r"^decisions-(\d*)([AB])\.jsonl$")
_SLOT_RE = re.compile(r"^(.+?) x(\d+)$")

WEIGHT_KEYS = ("fallDamage", "windCharge", "pearl", "height", "distance")
KIT_ITEMS = ("wind_charge", "ender_pearl", "golden_apple", "totem_of_undying")


# --------------------------------------------------------------------------
# run discovery
# --------------------------------------------------------------------------

def list_runs(gym_dir: Path | str = GYM_DIR) -> list[Path]:
    """All run directories under ``gym_dir``, oldest first."""
    gym_dir = Path(gym_dir)
    runs = [p for p in gym_dir.iterdir() if p.is_dir() and _RUN_RE.match(p.name)]
    return sorted(runs, key=lambda p: p.name)


def latest_runs(n: int = 2, min_episodes: int = 100,
                gym_dir: Path | str = GYM_DIR) -> list[Path]:
    """The ``n`` most recent runs with at least ``min_episodes`` episodes.

    ``min_episodes`` filters out short smoke-test runs so "long-term runs"
    means what it says.
    """
    keep = []
    for run in reversed(list_runs(gym_dir)):
        ep = run / "episodes.jsonl"
        if ep.exists() and _count_lines(ep) >= min_episodes:
            keep.append(run)
        if len(keep) == n:
            break
    return sorted(keep, key=lambda p: p.name)


def decision_files(run: Path | str) -> dict[str, Path]:
    """``{"0A": path, "0B": path, ...}`` for a run (``"A"``/``"B"`` with
    one pair, whose files have no pair number)."""
    out = {}
    for p in sorted(Path(run).iterdir()):
        m = _DECISION_FILE_RE.match(p.name)
        if m:
            out[m.group(1) + m.group(2)] = p
    return out


def _count_lines(path: Path) -> int:
    with open(path, "rb") as fh:
        return sum(1 for _ in fh)


def _iter_jsonl(path: Path) -> Iterable[dict]:
    with open(path, "rb") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                yield orjson.loads(line)
            except orjson.JSONDecodeError:
                # a run killed mid-write can leave a truncated last line
                continue


# --------------------------------------------------------------------------
# episodes
# --------------------------------------------------------------------------

def _side_row(ep: dict, side: str) -> dict:
    s, o = ep[side], ep["B" if side == "A" else "A"]
    cfg = s.get("config") or {}
    ocfg = o.get("config") or {}
    row = {
        "pair": ep["pair"],
        "episode": ep["episode"],
        "side": side,
        "agent": f'{ep["pair"]}{side}',
        "player": s.get("player"),
        "label": s.get("label"),
        "search": cfg.get("search"),
        "width": cfg.get("width"),
        "depth": cfg.get("depth"),
        "iterations": cfg.get("iterations"),
        "budget": cfg.get("budget"),
        **{f"w_{k}": cfg.get(k) for k in WEIGHT_KEYS},
        "opp_label": o.get("label"),
        "opp_search": ocfg.get("search"),
        "won": None if ep.get("winner") is None else ep["winner"] == side,
    }
    for k in ("dealt", "dealtRaw", "taken", "fall", "hits", "traded", "blocked",
              "windChargesUsed", "pearlsUsed", "applesEaten", "totemsPopped",
              "endHealth"):
        row[k] = s.get(k)
    for prefix, key in (("start", "start"), ("end", "end")):
        xyz = s.get(key) or [np.nan] * 3
        row[f"{prefix}_x"], row[f"{prefix}_y"], row[f"{prefix}_z"] = xyz
    return row


def load_episodes(run: Path | str) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Return ``(episodes, sides)``.

    ``episodes`` has one row per episode; ``sides`` has two rows per episode
    (one per side) with that side's config, stats and outcome, which is the
    shape you want for per-agent-type analysis.
    """
    run = Path(run)
    raw = list(_iter_jsonl(run / "episodes.jsonl"))
    ep_rows, side_rows = [], []
    for order, ep in enumerate(raw):
        exp = ep.get("expert") or {}
        ep_rows.append({
            "run": run.name,
            "order": order,  # finish order across all pairs
            "pair": ep["pair"],
            "episode": ep["episode"],
            "seed": ep.get("seed"),
            "reason": ep.get("reason"),
            "winner": ep.get("winner"),
            "ticks": ep.get("ticks"),
            "tickRate": ep.get("tickRate"),
            "explore": ep.get("explore"),
            "msptMean": ep.get("msptMean"),
            "msptMax": ep.get("msptMax"),
            "expert_label": f'{exp.get("search", "?")} {exp.get("width")}x{exp.get("depth")}',
            "A_label": ep["A"].get("label"),
            "B_label": ep["B"].get("label"),
        })
        for side in ("A", "B"):
            r = _side_row(ep, side)
            r["run"], r["order"] = run.name, order
            r["reason"], r["ticks"] = ep.get("reason"), ep.get("ticks")
            side_rows.append(r)
    episodes = pd.DataFrame(ep_rows)
    sides = pd.DataFrame(side_rows)
    for df in (episodes, sides):
        df["seconds"] = df["ticks"] / 20.0
    sides["won"] = sides["won"].astype("boolean")
    sides["net_damage"] = sides["dealt"] - sides["taken"]
    return episodes, sides


# --------------------------------------------------------------------------
# decision streams
# --------------------------------------------------------------------------

def _kit_counts(slots: list[str] | None) -> dict[str, int]:
    counts = dict.fromkeys(KIT_ITEMS, 0)
    for s in slots or ():
        m = _SLOT_RE.match(s)
        if m and m.group(1) in counts:
            counts[m.group(1)] += int(m.group(2))
    return counts


def _xyz(prefix: str, v) -> dict:
    v = v or (np.nan, np.nan, np.nan)
    return {f"{prefix}_x": v[0], f"{prefix}_y": v[1], f"{prefix}_z": v[2]}


def _parse_decision(d: dict, run: str, key: int, with_candidates: bool):
    me = d.get("self") or {}
    tg = d.get("target") or {}
    vit = me.get("vitals") or {}
    kit = me.get("kit") or {}
    cands = d.get("candidates") or []
    acted = d.get("acted") or {}
    expert = d.get("expert") or {}
    plan = d.get("plan") or {}

    ac = acted.get("chosen")
    ec = expert.get("chosen")
    chosen = cands[ac] if ac is not None and 0 <= ac < len(cands) else {}
    exp_c = cands[ec] if ec is not None and 0 <= ec < len(cands) else {}
    out = chosen.get("outcome") or {}

    sp, tp = me.get("pos"), tg.get("pos")
    dist = float(np.linalg.norm(np.subtract(tp, sp))) if sp and tp else np.nan
    hdist = (float(np.hypot(tp[0] - sp[0], tp[2] - sp[2])) if sp and tp else np.nan)

    row = {
        "run": run,
        "key": key,
        "side": d["side"],
        "episode": d["episode"],
        "clientTick": d.get("clientTick"),
        "tick": d.get("tick"),
        "wallMs": d.get("wallMs"),
        "triggers": "|".join(d.get("triggers") or []),
        **_xyz("self_pos", sp),
        **_xyz("self_vel", me.get("vel")),
        "self_onGround": me.get("onGround"),
        "self_fallDistance": me.get("fallDistance"),
        "self_impactY": me.get("impactY"),
        "self_yaw": me.get("yaw"),
        "self_pitch": me.get("pitch"),
        "self_sprinting": me.get("sprinting"),
        "self_attackStrength": me.get("attackStrengthTicker"),
        "self_health": vit.get("health"),
        "self_absorption": vit.get("absorption"),
        "self_selected": kit.get("selected"),
        "self_useTicks": me.get("useTicks"),
        **{f"kit_{k}": v for k, v in _kit_counts(kit.get("slots")).items()},
        **_xyz("tgt_pos", tp),
        **_xyz("tgt_vel", tg.get("vel")),
        "tgt_onGround": tg.get("onGround"),
        "tgt_health": tg.get("health"),
        "tgt_absorption": tg.get("absorption"),
        "tgt_shieldRaised": tg.get("shieldRaised"),
        "tgt_hurtTime": tg.get("hurtTime"),
        "dist": dist,
        "hdist": hdist,
        "dy": (tp[1] - sp[1]) if sp and tp else np.nan,
        "n_candidates": len(cands),
        "acted_idx": ac,
        "acted_type": chosen.get("type"),
        "acted_action": chosen.get("action"),
        "acted_why": acted.get("why"),
        "acted_ms": acted.get("ms"),
        "acted_value": acted.get("value"),
        "acted_explored": acted.get("explored"),
        "acted_out_ticks": out.get("ticks"),
        "acted_out_dealt": out.get("dealt"),
        "acted_out_taken": out.get("taken"),
        "acted_out_chance": out.get("chance"),
        "acted_out_failure": out.get("failure"),
        "plan_len": len(plan.get("expected") or []),
        "has_expert": bool(expert),
        "expert_idx": ec,
        "expert_type": exp_c.get("type"),
        "expert_ms": expert.get("ms"),
        "expert_value": expert.get("value"),
        "expert_agrees": (ac == ec) if expert else None,
    }

    cand_rows = []
    if with_candidates:
        a_rank, a_best = acted.get("rank") or [], acted.get("best") or []
        e_rank, e_best = expert.get("rank") or [], expert.get("best") or []
        at = lambda xs, i: xs[i] if i < len(xs) else np.nan  # noqa: E731
        for i, c in enumerate(cands):
            o = c.get("outcome") or {}
            p = c.get("params") or {}
            aim = p.get("pearlAim") or {}
            cand_rows.append({
                "run": run, "key": key, "side": d["side"],
                "episode": d["episode"], "idx": i,
                "type": c.get("type"), "action": c.get("action"),
                # action parameters; PearlCatch aims with pearlAim
                "p_yaw": aim.get("yaw", p.get("yaw")),
                "p_pitch": aim.get("pitch", p.get("pitch")),
                "p_delay": p.get("delay"),
                "p_minChance": p.get("minChance"),
                "p_eat": p.get("eat"),
                "p_jumpFirst": p.get("jumpFirst"),
                "p_forward": p.get("forward"),
                "p_pre": p.get("pre"),
                "p_why": p.get("why"),
                "p_ticks": p.get("ticks"),
                "p_awayYaw": p.get("awayYaw"),
                # the acting planner doesn't simulate every candidate
                "has_outcome": bool(o),
                **_xyz("end", o.get("endSelfPos")),
                "ticks": o.get("ticks"), "dealt": o.get("dealt"),
                "taken": o.get("taken"), "chance": o.get("chance"),
                "failure": o.get("failure"),
                "events": "|".join(o.get("events") or []),
                "acted_rank": at(a_rank, i), "acted_best": at(a_best, i),
                "expert_rank": at(e_rank, i), "expert_best": at(e_best, i),
                "is_acted": i == ac, "is_expert": i == ec,
            })
    return row, cand_rows


def _parse_file(args) -> dict[str, pd.DataFrame]:
    path, run, with_candidates = args
    dec, cand, ends, stops = [], [], [], []
    for key, d in enumerate(_iter_jsonl(Path(path))):
        t = d.get("type")
        if t == "decision":
            row, crow = _parse_decision(d, run, key, with_candidates)
            dec.append(row)
            cand.extend(crow)
        elif t == "action_end":
            payload = d.get("payload") or {}
            ends.append({
                "run": run, "key": key, "side": d["side"],
                "episode": d["episode"], "clientTick": d.get("clientTick"),
                "wallMs": d.get("wallMs"), "name": d.get("name"),
                "type": (d.get("name") or "").split("(")[0],
                "status": d.get("status"), "reason": d.get("reason"),
                "startTick": d.get("startTick"), "endTick": d.get("endTick"),
                "payloadType": d.get("payloadType"),
                "payload": orjson.dumps(payload).decode() if payload else None,
            })
        elif t == "stop":
            stops.append({
                "run": run, "side": d["side"], "episode": d["episode"],
                "clientTick": d.get("clientTick"), "wallMs": d.get("wallMs"),
                "reason": d.get("reason"), "replans": d.get("replans"),
                "expertLabeled": d.get("expertLabeled"),
                "expertSkipped": d.get("expertSkipped"),
            })
    tables = {"decisions": pd.DataFrame(dec), "actions": pd.DataFrame(ends),
              "stops": pd.DataFrame(stops)}
    if with_candidates:
        tables["candidates"] = pd.DataFrame(cand)
    for df in tables.values():
        if "side" in df:
            # one pair logs sides "A"/"B", several "0A", "0B", ...
            df["pair"] = df["side"].str[:-1].replace("", "0").astype(int)
            df["role"] = df["side"].str[-1]
    if not tables["actions"].empty:
        a = tables["actions"]
        a["duration"] = a["endTick"] - a["startTick"]
    return tables


@dataclass
class Decisions:
    decisions: pd.DataFrame
    actions: pd.DataFrame
    stops: pd.DataFrame
    candidates: pd.DataFrame | None = None


def _cache_paths(src: Path, with_candidates: bool) -> tuple[Path, list[str]]:
    st = src.stat()
    tag = f"v{CACHE_VERSION}-{st.st_size}-{int(st.st_mtime)}"
    names = ["decisions", "actions", "stops"] + (["candidates"] if with_candidates else [])
    return CACHE_DIR / src.parent.name / f"{src.stem}.{tag}", names


def _load_file(path: Path, run: str, with_candidates: bool, cache: bool):
    base, names = _cache_paths(path, with_candidates)
    files = {n: base.with_name(f"{base.name}.{n}.parquet") for n in names}
    if cache and all(f.exists() for f in files.values()):
        return {n: pd.read_parquet(f) for n, f in files.items()}
    tables = _parse_file((str(path), run, with_candidates))
    if cache:
        base.parent.mkdir(parents=True, exist_ok=True)
        # drop stale caches for this source file
        for old in base.parent.glob(f"{path.stem}.v*.parquet"):
            if not old.name.startswith(base.name + "."):
                old.unlink()
        for n, f in files.items():
            tables[n].to_parquet(f, index=False)
    return tables


def load_decisions(run: Path | str, sides: Iterable[str] | None = None,
                   with_candidates: bool = False, cache: bool = True,
                   workers: int | None = None) -> Decisions:
    """Parse every ``decisions-*.jsonl`` in a run (in parallel, cached).

    ``with_candidates`` additionally builds the one-row-per-candidate table
    (~8x the decision count), which is what you need for analysing the
    planner's scoring but is much bigger.
    """
    run = Path(run)
    files = decision_files(run)
    if sides is not None:
        files = {k: v for k, v in files.items() if k in set(sides)}
    jobs = [(p, run.name, with_candidates, cache) for p in files.values()]
    workers = workers or min(len(jobs), os.cpu_count() or 1)
    if workers > 1:
        with ProcessPoolExecutor(workers) as ex:
            parts = list(ex.map(_load_file_star, jobs))
    else:
        parts = [_load_file_star(j) for j in jobs]

    def cat(name):
        dfs = [p[name] for p in parts if name in p and not p[name].empty]
        return pd.concat(dfs, ignore_index=True) if dfs else pd.DataFrame()

    return Decisions(cat("decisions"), cat("actions"), cat("stops"),
                     cat("candidates") if with_candidates else None)


def _load_file_star(job):
    return _load_file(*job)


# --------------------------------------------------------------------------
# multi-run convenience
# --------------------------------------------------------------------------

def load_runs(runs: Iterable[Path | str], decisions: bool = True,
              with_candidates: bool = False) -> dict[str, pd.DataFrame]:
    """Load and concatenate several runs. Returns a dict of DataFrames keyed
    ``episodes``, ``sides`` and (if ``decisions``) ``decisions``, ``actions``,
    ``stops`` and optionally ``candidates``. Every table has a ``run`` column.
    """
    acc: dict[str, list[pd.DataFrame]] = {}
    for run in runs:
        ep, sd = load_episodes(run)
        acc.setdefault("episodes", []).append(ep)
        acc.setdefault("sides", []).append(sd)
        if decisions:
            d = load_decisions(run, with_candidates=with_candidates)
            for name in ("decisions", "actions", "stops", "candidates"):
                df = getattr(d, name)
                if df is not None:
                    acc.setdefault(name, []).append(df)
    return {k: pd.concat(v, ignore_index=True) for k, v in acc.items()}


def attach_action_outcome(decisions: pd.DataFrame,
                          actions: pd.DataFrame) -> pd.DataFrame:
    """Add the realised outcome (``status``, ``reason``, ``payloadType``,
    ``duration``) of the first action each decision started.

    The chosen action begins on the decision's ``clientTick``, so the match is
    ``action.startTick == decision.clientTick`` and ``action.name ==
    decision.acted_action`` within the same run/side/episode (the tick alone is
    ambiguous: an interrupted action and its replacement can share it).
    Later steps of a multi-step plan produce extra ``action_end`` rows that do
    not map to a decision and are left out.
    """
    keys = ["run", "side", "episode", "clientTick", "acted_action"]
    a = actions[["run", "side", "episode", "startTick", "name", "status",
                 "reason", "payloadType", "payload", "duration"]]
    a = a.rename(columns={"startTick": "clientTick", "name": "acted_action",
                          "status": "action_status", "reason": "action_reason"})
    a = a.drop_duplicates(keys)
    return decisions.merge(a, on=keys, how="left")


def attach_episode_info(df: pd.DataFrame, sides: pd.DataFrame) -> pd.DataFrame:
    """Join each decision/action row to its side's episode config and outcome
    (label, search, won, reason, ...) on ``run, pair, episode, role``."""
    cols = ["run", "pair", "episode", "side", "label", "search", "opp_label",
            "opp_search", "won", "reason", "ticks"]
    right = sides[cols].rename(columns={"side": "role", "ticks": "ep_ticks"})
    return df.merge(right, on=["run", "pair", "episode", "role"], how="left")
