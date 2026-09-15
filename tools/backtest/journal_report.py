#!/usr/bin/env python3
"""
Read one or more decision journals and answer: for the trades the engine took, what did each
shadow gate say, and how did the trades on each side of it do?

Works on journals written from 15 September 2026 on (the ones that carry market context, setup
shape and shadow-gate verdicts). Older journals print the trades without the gate columns.

Usage:
    python tools/backtest/journal_report.py data/journal/decisions-2026-09-15.jsonl [more files...]
"""
import json
import sys
from collections import defaultdict


def load(path):
    intents, outcomes = [], []
    with open(path, encoding="utf-8") as f:
        for line in f:
            try:
                r = json.loads(line)
            except ValueError:
                continue
            k = r.get("kind")
            if k == "intent" and r.get("userArmed", True):
                intents.append(r)
            elif k == "outcome":
                outcomes.append(r)
    return intents, outcomes


def pair(intents, outcomes):
    """Join each outcome to the last intent for the same symbol before it opened."""
    trades = []
    by_sym = defaultdict(list)
    for i in intents:
        by_sym[i["symbol"]].append(i)
    for o in outcomes:
        cands = [i for i in by_sym.get(o["symbol"], []) if i["ts"] <= o.get("openedAt", o["ts"])]
        if not cands:
            continue
        i = cands[-1]
        trades.append((i, o))
    return trades


def fmt(v, w=7, d=2):
    return f"{v:{w}.{d}f}" if isinstance(v, (int, float)) and v is not None else " " * (w - 1) + "-"


def main(paths):
    all_trades = []
    for p in paths:
        intents, outcomes = load(p)
        trades = pair(intents, outcomes)
        all_trades += trades
        print(f"\n=== {p}: {len(intents)} intents, {len(outcomes)} outcomes, {len(trades)} paired ===")
        print(f"{'time':8s} {'symbol':11s} {'pattern':8s} {'net':>8s} {'exit':10s} | {'nifty%':>7s} {'up%':>5s} {'climax':>6s} {'r15':>5s} {'vwap+':>5s} {'inPause':>7s} | gates")
        for i, o in trades:
            g = i.get("shadowGates", {})
            gates = " ".join(f"{k}={'Y' if v else 'n'}" for k, v in g.items()) if g else "(pre-capture journal)"
            t = i["ts"][11:19]
            hh, mm, ss = map(int, t.split(":"))
            ist = f"{(hh + 5 + (mm + 30) // 60) % 24:02d}:{(mm + 30) % 60:02d}:{ss:02d}"
            print(f"{ist:8s} {i['symbol']:11s} {str(i.get('pattern'))[:8]:8s} {fmt(o.get('netPnl'), 8, 0)} {str(o.get('exitReason')):10s} | "
                  f"{fmt(i.get('niftyFromOpenPct'))} {fmt(i.get('pctUpOnDay'), 5, 0)} {fmt(i.get('climaxBarAtr'), 6, 1)} "
                  f"{fmt(i.get('ret15m'), 5, 2)} {fmt(i.get('distFromVwapPct'), 5, 2)} {fmt(i.get('entryAbovePauseLowR'), 7, 2)} | {gates}")

    gated = [(i, o) for i, o in all_trades if i.get("shadowGates")]
    if gated:
        print(f"\n=== shadow gates over {len(gated)} trades with verdicts ===")
        print(f"{'gate':14s} {'pass n':>6s} {'pass win%':>9s} {'pass net':>9s} | {'fail n':>6s} {'fail win%':>9s} {'fail net':>9s}")
        for gate in ["climaxLe3Atr", "ret15Le1_5", "vwapExtLe1_6", "marketOk", "all"]:
            p = [(i, o) for i, o in gated if i["shadowGates"].get(gate)]
            f_ = [(i, o) for i, o in gated if not i["shadowGates"].get(gate)]
            def stats(g):
                if not g:
                    return "     -", "        -", "        -"
                wins = sum(1 for _, o in g if (o.get("netPnl") or 0) > 0)
                return f"{len(g):6d}", f"{100 * wins / len(g):8.0f}%", f"{sum(o.get('netPnl') or 0 for _, o in g):9.0f}"
            print(f"{gate:14s} {' '.join(stats(p))} | {' '.join(stats(f_))}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
