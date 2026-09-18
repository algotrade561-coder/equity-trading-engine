#!/usr/bin/env python3
"""
Backtest of the equity engine's long momentum strategy over archived 1-minute sessions.

A stand-alone re-implementation of the entry and exit logic in the Java engine, kept outside the
application on purpose. Everything the engine decides is re-decided here from the same inputs:

  * universe ranking by day change (top 50 enter, drop below 75 to leave) — only ranked stocks are
    evaluated, exactly as live, because only they are in full-depth mode;
  * indicators on completed 1-minute bars: session VWAP, EMA9/EMA20, ATR14, 5m/15m returns,
    relative strength vs NIFTY 50 over 15 minutes, within-session relative volume;
  * the setup state machine: IDLE -> IMPULSE -> PULLBACK|CONSOLIDATION -> ARMED -> TRIGGERED,
    with every mandatory gate, cooldown, hold-off and re-check at the trigger;
  * the risk gate: entry window, 3 open, 20 attempts, daily-loss latch, symbol cooldown, stop
    distance bounds, rupee-risk sizing capped by notional;
  * exits: hard stop, target, 90-minute time stop, 15:10 square-off, fixed stop (no breakeven);
  * charges as the engine computes them (brokerage, STT, exchange, SEBI, stamp, GST).

What is simulated, not real: ticks (each bar becomes a path open -> extreme -> extreme -> close),
fills (at the tick, 0.03% against you), the book (one-tick spread, so the spread gate passes), and
the previous close (from a per-day file, else carried from the prior session's last bar). Tapes
archived from 15 September 2026 carry four extra columns — the exchange's previous close and the
running day open/high/low as the engine held them when each bar closed — and those are used when
present. Older tapes lack them: the day high is then the bars' own high, which sits below the
exchange's, and "farFromDayHigh" lets through setups the live engine refused. That was the largest
difference from live on the September 2026 sessions and it is why the columns were added.

Usage:
    python tools/backtest/backtest.py --tape data/replay/tape --prev data/replay/previous-close \
        --from 2026-09-07 --to 2026-09-11 --out data/replay/py-out
"""
from __future__ import annotations

import argparse
import csv
import gzip
import json
import math
import os
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta, timezone
from typing import Dict, List, Optional

IST = timezone(timedelta(hours=5, minutes=30))
INDEX = "NIFTY 50"

# ── Settings: the house settings (RiskLimits.house / StrategyThresholds.house / ExitPolicy.house)
RISK = dict(
    risk_per_trade=3000.0, max_daily_loss=10000.0, max_open=3, max_attempts=20,
    max_position_value=200000.0, min_stop_pct=0.25, max_stop_pct=1.50, max_spread_pct=0.20,
    cooldown_s=300, max_staleness_s=15,
)
T = dict(
    min_day_change=0.75, max_day_change=3.5, sanity_band=25.0, max_dist_from_high=1.5,
    min_impulse_5m=0.6, min_rvol=1.3, require_vwap=True, require_ema=True, require_rs=True,
    min_pullback=0.2, max_pullback=1.2, max_consol_range=0.8, min_consol_bars=3,
    trigger_buffer=0.05, stop_atr=2.25, target_r=1.33, time_stop_min=90,
    window_start=time(9, 30), window_end=time(14, 30), square_off=time(15, 10),
)
ENTER_RANK, EXIT_RANK = 50, 75
MAX_BARS_ARMED = 10
INVALIDATION_COOLDOWN = timedelta(minutes=3)
ENTRY_RETRY_HOLD_OFF = timedelta(minutes=2)
REST_OF_SESSION = timedelta(hours=12)
RS_LOOKBACK = 15


# ── Data ─────────────────────────────────────────────────────────────────────

@dataclass
class Bar:
    symbol: str
    start: datetime
    open: float
    high: float
    low: float
    close: float
    volume: int
    # Day context at the bar's close, captured by the engine from the tick (archives from
    # 15 September 2026 on). None on older tapes.
    prev_close: Optional[float] = None
    day_open: Optional[float] = None
    day_high: Optional[float] = None
    day_low: Optional[float] = None


def load_tape(path: str) -> Dict[datetime, List[Bar]]:
    by_minute: Dict[datetime, List[Bar]] = defaultdict(list)
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8") as f:
        header = f.readline().strip()
        if not header.startswith("symbol,trading_date,start_time_utc"):
            raise SystemExit(f"{path}: not a candle archive ({header})")
        for line in f:
            p = line.rstrip("\n").split(",")
            if len(p) not in (8, 12):
                continue
            start = datetime.strptime(p[2], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
            bar = Bar(p[0], start, float(p[3]), float(p[4]), float(p[5]), float(p[6]), int(p[7]))
            if len(p) == 12:
                bar.prev_close, bar.day_open, bar.day_high, bar.day_low = (float(x) if x else None for x in p[8:12])
            by_minute[start].append(bar)
    return dict(sorted(by_minute.items()))


def load_previous_closes(path: str) -> Dict[str, float]:
    out: Dict[str, float] = {}
    if not os.path.isfile(path):
        return out
    with open(path, encoding="utf-8") as f:
        for row in csv.reader(f):
            if len(row) < 2:
                continue
            try:
                out[row[0].strip()] = float(row[1])
            except ValueError:
                pass
    return out


def locate(tape_dir: str, d: date) -> Optional[str]:
    for sub in (str(d.year), ""):
        for name in (f"candles-m1-{d}.csv.gz", f"candles-m1-{d}.csv"):
            p = os.path.join(tape_dir, sub, name)
            if os.path.isfile(p):
                return p
    return None


# ── Indicators (ports of com.equity.market.indicator.Indicators) ─────────────

def vwap(bars: List[Bar]) -> float:
    pv = v = 0.0
    for c in bars:
        typical = (c.high + c.low + c.close) / 3.0
        pv += typical * c.volume
        v += c.volume
    return pv / v if v > 0 else math.nan


def atr(bars: List[Bar], period: int) -> float:
    if len(bars) < period + 1:
        return math.nan
    s = 0.0
    for i in range(len(bars) - period, len(bars)):
        cur, prev_close = bars[i], bars[i - 1].close
        s += max(cur.high - cur.low, abs(cur.high - prev_close), abs(cur.low - prev_close))
    return s / period


def ema(bars: List[Bar], period: int) -> float:
    if len(bars) < period:
        return math.nan
    k = 2.0 / (period + 1)
    e = bars[len(bars) - period].close
    for i in range(len(bars) - period + 1, len(bars)):
        e = bars[i].close * k + e * (1 - k)
    return e


def return_pct(bars: List[Bar], lookback: int) -> float:
    if len(bars) < lookback + 1:
        return math.nan
    then, now = bars[-1 - lookback].close, bars[-1].close
    return (now - then) / then * 100.0 if then > 0 else math.nan


def session_rvol(bars: List[Bar]) -> float:
    if len(bars) < 5:
        return math.nan
    avg = sum(b.volume for b in bars) / len(bars)
    return bars[-1].volume / avg if avg > 0 else math.nan


def high_of_last(bars, n): return max(b.high for b in bars[-max(1, n):]) if bars else 0.0
def low_of_last(bars, n): return min(b.low for b in bars[-max(1, n):]) if bars else 0.0


def range_of_last(bars, n) -> float:
    w = bars[-max(1, n):]
    if len(w) < n:
        return float("inf")
    hi, lo = max(b.high for b in w), min(b.low for b in w)
    return (hi - lo) / lo * 100.0 if lo > 0 else float("inf")


# ── Per-instrument state (SharedInstrumentState) ────────────────────────────

@dataclass
class Inst:
    symbol: str
    prev_close: float = 0.0
    day_open: float = 0.0
    day_high: float = 0.0
    day_low: float = 0.0
    last: float = 0.0
    bars: List[Bar] = field(default_factory=list)
    vwap: float = math.nan
    ema9: float = math.nan
    ema20: float = math.nan
    atr: float = math.nan
    r5: float = math.nan
    r15: float = math.nan
    rvol: float = math.nan
    rs: float = math.nan
    rank: int = 0

    def day_change(self) -> float:
        return (self.last - self.prev_close) / self.prev_close * 100.0 if self.prev_close > 0 else 0.0

    def dist_from_high(self) -> float:
        return (self.day_high - self.last) / self.day_high * 100.0 if self.day_high > 0 else 0.0

    def above_vwap(self) -> bool:
        return self.vwap > 0 and self.last > self.vwap

    def suspect_ca(self) -> bool:
        return abs(self.day_change()) > T["sanity_band"]

    def recompute(self, index_r15: float):
        b = self.bars
        self.vwap, self.atr = vwap(b), atr(b, 14)
        self.ema9, self.ema20 = ema(b, 9), ema(b, 20)
        self.r5, self.r15 = return_pct(b, 5), return_pct(b, 15)
        self.rvol = session_rvol(b)
        self.rs = math.nan if (math.isnan(self.r15) or math.isnan(index_r15)) else self.r15 - index_r15


# ── Setup state machine (SetupState + MomentumStrategy) ──────────────────────

@dataclass
class Setup:
    state: str = "IDLE"
    pattern: Optional[str] = None
    impulse_high: float = 0.0
    structure_low: float = 0.0
    trigger: float = 0.0
    bars_in_pause: int = 0
    bars_since_armed: int = 0
    cooldown_until: Optional[datetime] = None
    retrigger_after: Optional[datetime] = None
    held_for_capacity: bool = False
    mandatory_ok: bool = True
    mandatory_failure: str = ""
    climax_atr: float = 0.0
    impulse_at: Optional[datetime] = None

    def move_to(self, s):
        if self.state != s:
            self.state = s
            if s == "ARMED":
                self.bars_since_armed = 0

    def invalidate(self, now: datetime, cooldown: timedelta):
        self.impulse_high = self.structure_low = self.trigger = 0.0
        self.climax_atr = 0.0
        self.impulse_at = None
        self.bars_in_pause = self.bars_since_armed = 0
        self.pattern = None
        self.cooldown_until = now + cooldown
        self.move_to("IDLE")

    def hold_off(self, until: datetime, for_capacity=False):
        self.retrigger_after = until
        self.held_for_capacity = for_capacity
        self.move_to("ARMED")


class Rejection(Exception):
    def __init__(self, stage, condition, detail=""):
        super().__init__(f"{stage}:{condition}:{detail}")
        self.stage, self.condition, self.detail = stage, condition, detail


# ── Positions & costs ────────────────────────────────────────────────────────

def round_trip_cost(buy_value: float, sell_value: float) -> float:
    if not (buy_value > 0 and sell_value > 0):
        return 0.0
    brokerage = min(20.0, buy_value * 0.0003) + min(20.0, sell_value * 0.0003)
    turnover = buy_value + sell_value
    stt = sell_value * 0.00025
    exchange = turnover * 0.0000297
    sebi = turnover * 0.000001
    stamp = buy_value * 0.00003
    gst = (brokerage + exchange + sebi) * 0.18
    return brokerage + stt + exchange + sebi + stamp + gst


@dataclass
class Position:
    symbol: str
    pattern: str
    qty: int
    entry_at: datetime
    intended: float
    entry: float
    stop: float
    target: float
    exit_at: Optional[datetime] = None
    exit: float = 0.0
    reason: Optional[str] = None
    mfe: float = 0.0
    mae: float = 0.0

    @property
    def open(self): return self.exit_at is None

    def gross(self): return (self.exit - self.entry) * self.qty if not self.open else 0.0

    def net(self): return self.gross() - round_trip_cost(self.entry * self.qty, self.exit * self.qty)

    def r_multiple(self):
        risk = (self.entry - self.stop) * self.qty
        return self.net() / risk if risk > 0 and not self.open else 0.0


# ── The session ──────────────────────────────────────────────────────────────

class Session:
    def __init__(self, d: date, tape: Dict[datetime, List[Bar]], prev: Dict[str, float], args, log):
        self.d, self.tape, self.args, self.log = d, tape, args, log
        self.inst: Dict[str, Inst] = {}
        self.setups: Dict[str, Setup] = defaultdict(Setup)
        self.discovery: set = set()
        self.previous_ranks: Dict[str, int] = {}
        self.positions: List[Position] = []
        self.attempts = 0
        self.realised = 0.0
        self.latched = False
        self.last_closed: Dict[str, datetime] = {}
        self.slip = args.slippage / 100.0
        self.now: datetime = datetime.combine(d, time(9, 14), IST)
        self.squared_off = False
        self.next_rank = self.next_guard = self.now
        for sym in {b.symbol for bars in tape.values() for b in bars}:
            i = Inst(sym)
            i.prev_close = prev.get(sym, 0.0)
            self.inst[sym] = i
        self.stocks = [s for s in self.inst if s != INDEX]

    # ── helpers
    def ist(self, t: datetime) -> time:
        return t.astimezone(IST).time().replace(microsecond=0)

    def open_positions(self) -> List[Position]:
        return [p for p in self.positions if p.open]

    def unrealised(self) -> float:
        return sum((self.inst[p.symbol].last - p.entry) * p.qty for p in self.open_positions())

    # ── ranking (TopGainerEngine + UniverseService.refreshDiscoverySet)
    def refresh_ranking(self):
        eligible = [i for s, i in self.inst.items() if s != INDEX and i.prev_close > 0 and i.last > 0 and not i.suspect_ca()]
        eligible.sort(key=lambda i: i.day_change(), reverse=True)
        nxt = set()
        for rank, i in enumerate(eligible, start=1):
            i.rank = rank
            if rank <= (EXIT_RANK if i.symbol in self.discovery else ENTER_RANK):
                nxt.add(i.symbol)
        self.discovery = nxt

    # ── candle path (MomentumStrategy.evaluateCandle)
    def on_candle_closed(self, i: Inst):
        if i.symbol not in self.discovery:
            return
        s = self.setups[i.symbol]
        now = self.now
        try:
            if len(i.bars) < 20:
                raise Rejection("DISCOVERY", "insufficientHistory")
            if s.state == "ARMED":
                s.bars_since_armed += 1
                if s.bars_since_armed > MAX_BARS_ARMED:
                    s.invalidate(now, INVALIDATION_COOLDOWN)
                    raise Rejection("ARMING", "armedTooLong")
            try:
                self.check_mandatory(i, s)
                s.mandatory_ok, s.mandatory_failure = True, ""
            except Rejection as r:
                s.mandatory_ok, s.mandatory_failure = False, r.condition
                raise
            if s.cooldown_until and now < s.cooldown_until:
                raise Rejection("DISCOVERY", "symbolCooldown")
            if s.state == "IDLE":
                self.try_impulse(i, s)
            elif s.state == "IMPULSE":
                self.try_pause(i, s)
            elif s.state in ("PULLBACK", "CONSOLIDATION"):
                self.try_arm(i, s)
        except Rejection as r:
            self.rejections[r.condition] += 1

    def check_mandatory(self, i: Inst, s: Setup):
        now = self.now
        if i.prev_close <= 0:
            raise Rejection("UNIVERSE", "noPreviousClose")
        if i.suspect_ca():
            s.invalidate(now, INVALIDATION_COOLDOWN)
            raise Rejection("UNIVERSE", "suspectCorporateAction")
        if math.isnan(i.vwap) or math.isnan(i.ema9) or math.isnan(i.ema20) or (T["require_rs"] and math.isnan(i.rs)):
            raise Rejection("DISCOVERY", "indicatorsNotReady")
        ch = i.day_change()
        if ch < T["min_day_change"]:
            raise Rejection("DISCOVERY", "dayChangeTooSmall")
        if ch > T["max_day_change"]:
            raise Rejection("DISCOVERY", "dayChangeExtended")
        if T["require_vwap"] and not i.above_vwap():
            s.invalidate(now, INVALIDATION_COOLDOWN)
            raise Rejection("DISCOVERY", "belowVwap")
        if T["require_ema"] and not (i.ema9 > i.ema20):
            raise Rejection("DISCOVERY", "emaNotStacked")
        if T["require_rs"] and not (i.rs > 0):
            raise Rejection("DISCOVERY", "notOutperformingIndex")
        if i.dist_from_high() > T["max_dist_from_high"]:
            raise Rejection("DISCOVERY", "farFromDayHigh")
        t = self.ist(now)
        if t < T["window_start"] or t > T["window_end"]:
            raise Rejection("DISCOVERY", "outsideEntryWindow")

    def try_impulse(self, i: Inst, s: Setup):
        if not (i.r5 >= T["min_impulse_5m"]):
            raise Rejection("IMPULSE", "thrustTooWeak")
        if not (i.rvol >= T["min_rvol"]):
            raise Rejection("IMPULSE", "noVolumeBehindThrust")
        s.impulse_high, s.bars_in_pause = high_of_last(i.bars, 5), 0
        s.climax_atr = (max(b.high - b.low for b in i.bars[-5:]) / i.atr) if i.atr and i.atr > 0 else 0.0
        if s.state != "IMPULSE":
            s.impulse_at = self.now
        s.move_to("IMPULSE")

    def try_pause(self, i: Inst, s: Setup):
        last = i.bars[-1]
        impulse_high = max(s.impulse_high, last.high)
        retr = (impulse_high - last.low) / impulse_high * 100.0 if impulse_high > 0 else 0.0
        if retr > T["max_pullback"]:
            s.invalidate(self.now, INVALIDATION_COOLDOWN)
            raise Rejection("PULLBACK", "retracedTooDeep")
        n = T["min_consol_bars"]
        if range_of_last(i.bars, n) <= T["max_consol_range"]:
            s.pattern, s.structure_low, s.bars_in_pause = "CONSOLIDATION_BREAKOUT", low_of_last(i.bars, n), 1
            s.move_to("CONSOLIDATION")
            return
        if retr >= T["min_pullback"]:
            s.pattern, s.structure_low, s.bars_in_pause = "PULLBACK_CONTINUATION", last.low, 1
            s.move_to("PULLBACK")
            return
        s.impulse_high, s.bars_in_pause = impulse_high, 0
        if i.atr and i.atr > 0:
            s.climax_atr = max(s.climax_atr, max(b.high - b.low for b in i.bars[-5:]) / i.atr)
        s.move_to("IMPULSE")

    def try_arm(self, i: Inst, s: Setup):
        last = i.bars[-1]
        if last.low < s.structure_low:
            s.invalidate(self.now, INVALIDATION_COOLDOWN)
            raise Rejection("CONSOLIDATION", "pauseLowBroken")
        s.bars_in_pause += 1
        if last.low > s.structure_low:
            s.structure_low = last.low
        if s.bars_in_pause < T["min_consol_bars"]:
            raise Rejection("CONSOLIDATION", "pauseTooShort")
        pause_high = high_of_last(i.bars, s.bars_in_pause)
        s.trigger = pause_high * (1 + T["trigger_buffer"] / 100.0)
        s.move_to("ARMED")

    # ── tick path (MomentumStrategy.evaluateTick + RiskEngine + PositionLifecycle.open)
    def on_tick(self, i: Inst, price: float):
        if i.symbol not in self.discovery:
            return
        s = self.setups[i.symbol]
        if s.state != "ARMED":
            return
        if s.retrigger_after and self.now < s.retrigger_after:
            return
        if not s.mandatory_ok:
            self.rejections["mandatoryFailedAtLastClose"] += 1
            return
        if price < s.trigger:
            return
        # recheckAtTrigger
        ch = i.day_change()
        if i.suspect_ca():
            s.invalidate(self.now, INVALIDATION_COOLDOWN)
            self.rejections["suspectCorporateActionAtTrigger"] += 1
            return
        if ch > T["max_day_change"]:
            self.rejections["dayChangeExtendedAtTrigger"] += 1
            return
        if ch < T["min_day_change"]:
            self.rejections["dayChangeTooSmallAtTrigger"] += 1
            return
        if T["require_vwap"] and not i.above_vwap():
            self.rejections["belowVwapAtTrigger"] += 1
            return
        if not (i.rvol >= T["min_rvol"]):
            self.rejections["volumeFadedByTrigger"] += 1
            return
        # ── experimental extension gates (off unless set on the command line) ──
        if self.args.max_vwap_ext is not None and i.vwap > 0 and (price - i.vwap) / price * 100.0 > self.args.max_vwap_ext:
            self.rejections["tooFarAboveVwap(exp)"] += 1
            return
        if self.args.max_r15 is not None and not math.isnan(i.r15) and i.r15 > self.args.max_r15:
            self.rejections["ran15mTooFast(exp)"] += 1
            return
        if self.args.max_minutes_since_impulse is not None and s.impulse_at is not None                 and (self.now - s.impulse_at) > timedelta(minutes=self.args.max_minutes_since_impulse):
            self.rejections["staleImpulse(exp)"] += 1
            return
        if self.args.max_rank is not None and i.rank > self.args.max_rank:
            self.rejections["rankTooLow(exp)"] += 1
            return
        if self.args.max_climax_atr is not None and s.climax_atr > self.args.max_climax_atr:
            self.rejections["climaxBar(exp)"] += 1
            return
        if self.args.market_gate:
            idx = self.inst.get(INDEX)
            if idx is not None and idx.bars:
                from_open = (idx.last / idx.bars[0].open - 1) * 100.0 if idx.bars[0].open > 0 else math.nan
                r60 = return_pct(idx.bars, 60)
                if (not math.isnan(from_open) and from_open < -0.3) or (not math.isnan(r60) and r60 < -0.2):
                    self.rejections["marketWeak(exp)"] += 1
                    return
        entry = price
        stop = s.structure_low
        if not math.isnan(i.atr) and i.atr > 0:
            stop = min(stop, entry - T["stop_atr"] * i.atr)
        if not (stop > 0) or stop >= entry:
            self.rejections["noValidStopLevel"] += 1
            return
        target = entry + (entry - stop) * T["target_r"]
        s.move_to("TRIGGERED")
        self.intents += 1
        self.attempt_entry(i, s, entry, stop, target)

    def attempt_entry(self, i: Inst, s: Setup, entry: float, stop: float, target: float):
        now = self.now
        denial = None
        t = self.ist(now)
        if t < T["window_start"] or t > T["window_end"]:
            denial = "OUTSIDE_ENTRY_WINDOW"
        elif self.check_latch():
            denial = "DAILY_LOSS_LATCHED"
        elif self.attempts >= RISK["max_attempts"]:
            denial = "MAX_DAILY_ATTEMPTS"
        elif len(self.open_positions()) >= RISK["max_open"]:
            denial = "MAX_OPEN_POSITIONS"
        elif any(p.symbol == i.symbol for p in self.open_positions()):
            denial = "ALREADY_IN_SYMBOL"
        elif i.symbol in self.last_closed and (now - self.last_closed[i.symbol]) < timedelta(seconds=RISK["cooldown_s"]):
            denial = "SYMBOL_COOLDOWN"
        else:
            stop_pct = (entry - stop) / entry * 100.0
            if stop_pct < RISK["min_stop_pct"]:
                denial = "STOP_TOO_TIGHT"
            elif stop_pct > RISK["max_stop_pct"]:
                denial = "STOP_TOO_WIDE"
            else:
                qty = int(math.floor(RISK["risk_per_trade"] / (entry - stop)))
                by_value = int(math.floor(RISK["max_position_value"] / entry))
                if qty <= 0:
                    denial = "SIZE_ROUNDS_TO_ZERO"
                elif by_value <= 0:
                    denial = "POSITION_VALUE_CAP"
                else:
                    qty = min(qty, by_value)
        if denial:
            self.denials[denial] += 1
            if denial in ("MAX_OPEN_POSITIONS",):
                s.hold_off(now + REST_OF_SESSION, for_capacity=True)
            elif denial in ("MAX_DAILY_ATTEMPTS", "DAILY_LOSS_LATCHED"):
                s.hold_off(now + REST_OF_SESSION)
            else:
                s.hold_off(now + ENTRY_RETRY_HOLD_OFF)
            return
        self.attempts += 1
        fill = round(entry * (1 + self.slip), 2)
        self.positions.append(Position(i.symbol, s.pattern, qty, now, entry, fill, stop, target))
        self.log(f"  ENTRY {self.ist(now)} {i.symbol:11s} {s.pattern:22s} x{qty:<5d} @ {fill:9.2f} stop {stop:9.2f} target {target:9.2f}")

    def check_latch(self) -> bool:
        if self.latched:
            return True
        if self.realised + self.unrealised() <= -abs(RISK["max_daily_loss"]):
            self.latched = True
            self.log(f"  DAILY LOSS LATCHED at {self.ist(self.now)}: realised {self.realised:.0f} + open {self.unrealised():.0f}")
        return self.latched

    # ── exits (PositionLifecycle.onTick / checkTimeStops / requestExitAll)
    def close(self, p: Position, price: float, reason: str):
        p.exit_at, p.reason = self.now, reason
        p.exit = round(price * (1 - self.slip), 2)
        self.realised += p.net()
        self.last_closed[p.symbol] = self.now
        s = self.setups[p.symbol]
        s.invalidate(self.now, timedelta(seconds=RISK["cooldown_s"]))
        for other in self.setups.values():          # capacity freed
            if other.held_for_capacity:
                other.held_for_capacity, other.retrigger_after = False, None
        self.log(f"  EXIT  {self.ist(self.now)} {p.symbol:11s} {reason:10s} @ {p.exit:9.2f}  net {p.net():9.2f}  R {p.r_multiple():5.2f}")

    def check_exits_on_tick(self, i: Inst, price: float):
        for p in self.open_positions():
            if p.symbol != i.symbol:
                continue
            p.mfe = max(p.mfe, price - p.entry)
            p.mae = max(p.mae, p.entry - price)
            if price <= p.stop:
                self.close(p, price, "HARD_STOP")
            elif price >= p.target:
                self.close(p, price, "TARGET")

    def guards(self):
        t = self.ist(self.now)
        cutoff = self.now - timedelta(minutes=T["time_stop_min"])
        for p in self.open_positions():
            if p.entry_at < cutoff:
                self.close(p, self.inst[p.symbol].last, "TIME_STOP")
        if t >= T["square_off"]:
            for p in self.open_positions():
                self.close(p, self.inst[p.symbol].last, "SQUARE_OFF")

    # ── the loop
    def run(self):
        self.rejections = defaultdict(int)
        self.denials = defaultdict(int)
        self.intents = 0
        open_t = datetime.combine(self.d, time(9, 15), IST)
        end_t = datetime.combine(self.d, time(15, 30), IST)
        n = max(4, self.args.ticks_per_bar)
        per_leg = max(1, (n - 1) // 3)
        pending_close: List[Inst] = []

        for minute, bars in self.tape.items():
            if minute < open_t or minute >= end_t:
                continue
            self.advance(minute, pending_close)
            pending_close = []
            paths = []
            for b in bars:
                i = self.inst[b.symbol]
                if b.prev_close and self.args.use_captured:
                    i.prev_close = b.prev_close          # the exchange's figure, as the engine saw it
                if i.day_open == 0:
                    i.day_open = b.day_open if (b.day_open and self.args.use_captured) else b.open
                up = b.close >= b.open
                first, second = (b.low, b.high) if up else (b.high, b.low)
                path = [b.open]
                for a, c in ((b.open, first), (first, second), (second, b.close)):
                    path += [a + (c - a) * k / per_leg for k in range(1, per_leg + 1)]
                paths.append((i, b, [round(x, 2) for x in path]))
            width = len(paths[0][2]) if paths else 0
            step = 59.0 / max(1, width - 1)
            for k in range(width):
                self.advance(minute + timedelta(seconds=step * k), [])
                for i, b, path in paths:
                    price = path[k]
                    i.last = price
                    i.day_high = max(i.day_high, price)
                    i.day_low = price if i.day_low == 0 else min(i.day_low, price)
                    self.check_exits_on_tick(i, price)
                    self.on_tick(i, price)
            for i, b, path in paths:
                i.bars.append(b)
                if self.args.use_captured:
                    # The exchange's running high/low as the engine held them when this bar closed —
                    # applied at the close, which is when the engine's own reading reached them.
                    if b.day_high:
                        i.day_high = max(i.day_high, b.day_high)
                    if b.day_low:
                        i.day_low = b.day_low if i.day_low == 0 else min(i.day_low, b.day_low)
                pending_close.append(i)
        self.advance(end_t, pending_close)
        self.advance(end_t + timedelta(minutes=2), [])
        return self

    def advance(self, to: datetime, closes: List[Inst]):
        """Move tape time to `to`, closing bars first (they close on the next minute's first tick),
        then running the periodic work that fell due (ranking every 15 s, guards every 10 s)."""
        if closes:
            self.now = max(self.now, to)
            index_bars = self.inst[INDEX].bars if INDEX in self.inst else []
            idx_r15 = return_pct(index_bars, RS_LOOKBACK)
            for i in closes:
                i.recompute(idx_r15)
            for i in closes:
                if i.symbol != INDEX:
                    self.on_candle_closed(i)
        while self.now < to:
            self.now = min(to, self.now + timedelta(seconds=1))
            if self.now >= self.next_rank:
                self.refresh_ranking()
                self.next_rank = self.now + timedelta(seconds=15)
            if self.now >= self.next_guard:
                self.guards()
                self.next_guard = self.now + timedelta(seconds=10)


# ── Live comparison from the decision journal ────────────────────────────────

def live_trades(journal_path: str):
    if not os.path.isfile(journal_path):
        return []
    trades, open_by_sym = [], defaultdict(list)
    with open(journal_path, encoding="utf-8") as f:
        for line in f:
            try:
                r = json.loads(line)
            except ValueError:
                continue
            kind, sym = r.get("kind"), r.get("symbol")
            if kind == "intent" and r.get("armed", True):
                t = dict(symbol=sym, at=r["ts"][11:19], entry=r.get("entry"), pattern=r.get("pattern"), reason=None, pnl=None)
                trades.append(t)
                open_by_sym[sym].append(t)
            elif kind == "outcome" and open_by_sym.get(sym):
                t = open_by_sym[sym].pop(0)
                t["reason"], t["pnl"], t["exit"] = r.get("exitReason"), r.get("pnl"), r.get("exit")
    # a retried intent within a few minutes is one decision
    out = []
    for t in trades:
        if out and out[-1]["symbol"] == t["symbol"] and out[-1]["reason"] is None and t["at"] > out[-1]["at"]:
            out[-1] = t
            continue
        out.append(t)
    return out


def utc_hhmmss_to_ist(s: str) -> str:
    h, m, sec = map(int, s.split(":"))
    return (datetime(2000, 1, 1, h, m, sec, tzinfo=timezone.utc).astimezone(IST)).strftime("%H:%M:%S")


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tape", default="data/replay/tape")
    ap.add_argument("--prev", default="data/replay/previous-close")
    ap.add_argument("--journal", default="data/journal", help="live decision journals for comparison ('' to skip)")
    ap.add_argument("--from", dest="date_from", required=True)
    ap.add_argument("--to", dest="date_to")
    ap.add_argument("--out", default="data/replay/py-out")
    ap.add_argument("--slippage", type=float, default=0.03, help="adverse slippage per fill, percent")
    ap.add_argument("--ticks-per-bar", type=int, default=10)
    ap.add_argument("--max-vwap-ext", type=float, default=None,
                    help="experimental: refuse a trigger more than this %% above session VWAP")
    ap.add_argument("--max-r15", type=float, default=None,
                    help="experimental: refuse a trigger whose 15-minute return exceeds this %%")
    ap.add_argument("--window-start", default=None, help="experimental: entry window start HH:MM (default 09:30)")
    ap.add_argument("--max-minutes-since-impulse", type=int, default=None,
                    help="experimental: refuse a trigger more than this many minutes after the impulse began")
    ap.add_argument("--max-rank", type=int, default=None, help="experimental: refuse a trigger on a stock ranked worse than this")
    ap.add_argument("--max-climax-atr", type=float, default=None,
                    help="experimental: refuse a setup whose run-up had a bar taller than this many ATRs")
    ap.add_argument("--market-gate", action="store_true",
                    help="experimental: refuse when NIFTY is below -0.3%% from its open or fell >0.2%% over the last hour")
    ap.add_argument("--no-captured", dest="use_captured", action="store_false",
                    help="ignore the day context columns in the tape even when present")
    args = ap.parse_args()
    if args.window_start:
        hh, mm = map(int, args.window_start.split(":"))
        T["window_start"] = time(hh, mm)

    os.makedirs(args.out, exist_ok=True)
    lines: List[str] = []

    def log(s=""):
        print(s)
        lines.append(s)

    d0 = date.fromisoformat(args.date_from)
    d1 = date.fromisoformat(args.date_to) if args.date_to else d0
    all_positions = []
    carried: Dict[str, float] = {}
    d = d0
    while d <= d1:
        path = locate(args.tape, d)
        if path:
            tape = load_tape(path)
            prev = dict(carried)
            from_file = load_previous_closes(os.path.join(args.prev, f"previous-close-{d}.csv"))
            prev.update(from_file)
            first_minute = min(tape).astimezone(IST).strftime("%H:%M") if tape else "-"
            log(f"\n=== {d}: {sum(len(v) for v in tape.values())} bars, tape starts {first_minute} IST, "
                f"previous closes {len(from_file)} from file + {len(prev) - len(from_file)} carried ===")
            s = Session(d, tape, prev, args, log).run()
            closed = [p for p in s.positions if not p.open]
            day_net = sum(p.net() for p in closed)
            log(f"  day: {len(closed)} trades, {sum(1 for p in closed if p.net() > 0)} wins, net P&L {day_net:,.2f} "
                f"| intents {s.intents}, denials {dict(s.denials)}")
            for p in closed:
                all_positions.append((d, p))
            # carry each symbol's last close forward
            carried = {}
            for bars in tape.values():
                for b in bars:
                    carried[b.symbol] = b.close
            if args.journal:
                live = live_trades(os.path.join(args.journal, f"decisions-{d}.jsonl"))
                if live:
                    log(f"  --- live took {len(live)}; backtest took {len(closed)} ---")
                    unmatched = list(closed)
                    matched = 0
                    for t in live:
                        best, gap = None, 10 ** 9
                        for p in unmatched:
                            if p.symbol != t["symbol"]:
                                continue
                            g = abs((p.entry_at.astimezone(IST).strftime("%H:%M:%S") > utc_hhmmss_to_ist(t["at"])) * 0
                                    + (datetime.strptime(p.entry_at.astimezone(IST).strftime("%H:%M:%S"), "%H:%M:%S")
                                       - datetime.strptime(utc_hhmmss_to_ist(t["at"]), "%H:%M:%S")).total_seconds())
                            if g < gap:
                                best, gap = p, g
                        if best and gap <= 900:
                            unmatched.remove(best)
                            matched += 1
                            same = (t["reason"] or "-") == best.reason
                            log(f"    {t['symbol']:11s} live {utc_hhmmss_to_ist(t['at'])} {t['entry']:>9.2f} {t['reason'] or '-':10s} "
                                f"{(t['pnl'] or 0):>8.0f} | bt {best.entry_at.astimezone(IST).strftime('%H:%M:%S')} {best.entry:>9.2f} "
                                f"{best.reason:10s} {best.net():>8.0f} | {'match' if same else 'different exit'}")
                        else:
                            log(f"    {t['symbol']:11s} live {utc_hhmmss_to_ist(t['at'])} {t['entry']:>9.2f} {t['reason'] or '-':10s} "
                                f"{(t['pnl'] or 0):>8.0f} | bt      -             -            - | LIVE ONLY")
                    for p in unmatched:
                        log(f"    {p.symbol:11s} live     -             -                 - | bt {p.entry_at.astimezone(IST).strftime('%H:%M:%S')} "
                            f"{p.entry:>9.2f} {p.reason:10s} {p.net():>8.0f} | BACKTEST ONLY")
                    log(f"  matched {matched} of {len(live)} live entries; {len(unmatched)} backtest-only")
        else:
            log(f"\n=== {d}: no tape ===")
        d += timedelta(days=1)

    # ── totals and trade table
    log("\n=== ALL TRADES ===")
    log(f"{'date':10s} {'symbol':11s} {'pattern':22s} {'qty':>6s} {'entry@':>8s} {'entry':>9s} {'stop':>9s} {'target':>9s} "
        f"{'exit@':>8s} {'exit':>9s} {'reason':10s} {'gross':>9s} {'net':>9s} {'R':>6s}")
    with open(os.path.join(args.out, "trades.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["date", "symbol", "pattern", "qty", "entry_at_ist", "entry", "stop", "target", "exit_at_ist", "exit",
                    "reason", "gross", "charges", "net", "r_multiple", "mfe_pct", "mae_pct"])
        for d, p in all_positions:
            ea, xa = p.entry_at.astimezone(IST).strftime("%H:%M:%S"), p.exit_at.astimezone(IST).strftime("%H:%M:%S")
            log(f"{d} {p.symbol:11s} {p.pattern:22s} {p.qty:6d} {ea:>8s} {p.entry:9.2f} {p.stop:9.2f} {p.target:9.2f} "
                f"{xa:>8s} {p.exit:9.2f} {p.reason:10s} {p.gross():9.2f} {p.net():9.2f} {p.r_multiple():6.2f}")
            w.writerow([d, p.symbol, p.pattern, p.qty, ea, f"{p.entry:.2f}", f"{p.stop:.2f}", f"{p.target:.2f}", xa,
                        f"{p.exit:.2f}", p.reason, f"{p.gross():.2f}", f"{p.gross() - p.net():.2f}", f"{p.net():.2f}",
                        f"{p.r_multiple():.3f}", f"{p.mfe / p.entry * 100:.3f}", f"{p.mae / p.entry * 100:.3f}"])

    closed = [p for _, p in all_positions]
    wins = [p for p in closed if p.net() > 0]
    losses = [p for p in closed if p.net() <= 0]
    net = sum(p.net() for p in closed)
    gross = sum(p.gross() for p in closed)
    log(f"\n=== TOTAL {d0}..{d1}: {len(closed)} trades, {len(wins)} wins ({(100 * len(wins) / len(closed)) if closed else 0:.0f}%), "
        f"gross {gross:,.2f}, charges {gross - net:,.2f}, NET P&L {net:,.2f} ===")
    if wins:
        log(f"  avg win {sum(p.net() for p in wins) / len(wins):,.0f}   avg loss {sum(p.net() for p in losses) / len(losses) if losses else 0:,.0f}"
            f"   expectancy/trade {net / len(closed):,.0f}   avg R {sum(p.r_multiple() for p in closed) / len(closed):.2f}")
    by_reason = defaultdict(lambda: [0, 0, 0.0])
    by_pattern = defaultdict(lambda: [0, 0, 0.0])
    for p in closed:
        for key, table in ((p.reason, by_reason), (p.pattern, by_pattern)):
            table[key][0] += 1
            table[key][1] += 1 if p.net() > 0 else 0
            table[key][2] += p.net()
    for name, table in (("by exit reason", by_reason), ("by pattern", by_pattern)):
        log(f"  {name}:")
        for k, (n, w_, pnl) in sorted(table.items()):
            log(f"    {k:24s} {n:3d} trades  {w_:3d} wins  net {pnl:10,.2f}")
    with open(os.path.join(args.out, "report.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"\nwritten: {os.path.join(args.out, 'report.txt')} and trades.csv")


if __name__ == "__main__":
    main()
