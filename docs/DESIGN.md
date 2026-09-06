# equity — technical design

Intraday **LONG** equity momentum. Multi-user. `LIVE` and `REPLAY` only — there is no paper mode.

---

## 0. Where I would change the specification

Listed first because these change the architecture. Each is a correctness, race-condition or
trading-safety issue rather than a preference. Several come from measured failures in a sibling
options engine, and those are cited.

### 0.1 — CRITICAL: exits must not share a permission gate with entries

The spec routes everything through one `TradingPermissionResult`, and §44 keeps
`MANAGE_EXISTING_POSITIONS` alive during an emergency halt. But *management is orders*. If a halt
blocks order submission while a position is open, its structural stop can no longer fire — the halt
has silently converted a protected position into an unhedged one. That is the opposite of safe.

**Change:** two permissions, evaluated separately.

    EntryPermission  — denied by halt, pause, risk, time window, staleness, reconciliation...
    ExitPermission   — denied by almost nothing; only a broker-side outage stops an exit.

A halt must *reduce* exposure, never strand it.

### 0.2 — CRITICAL: exactly one component may close a position

The spec lets `ExitEngine`, `MomentumRideEngine`, `RiskEngine`, `EmergencyHaltEngine` and the EOD
job all end a position. In the sibling engine this exact shape produced **29% of one strategy's
trades being closed by a path that did not own them**, and two trailing stops racing the same
position with the tighter one always winning — which defeated the wider stop's entire purpose.

**Change:** `PositionManager` is the **sole** owner of a position lifecycle and the only caller of
the broker exit path. Everything else submits an `ExitRequest(positionId, reason, priority)` to a
per-position queue. `PositionManager` collapses them by priority, deterministically. Every exit then
has exactly one author, and the author is always recorded.

    ExitPriority: EMERGENCY > RISK_LIQUIDATION > EOD_MANDATORY > STRUCTURAL_STOP > STRUCTURE_BREAK

### 0.3 — CRITICAL: the §41 checklist does not close the §48 race

Re-checking halt at step 12 and submitting at step 13 narrows the window; it does not close it, and
ordering alone never can, because the broker call crosses a network boundary.

**Change:** a per-user **trading epoch**.

    epoch = AtomicLong per user
    halt / pause / risk-trip   -> epoch.incrementAndGet()
    intent creation            -> capture epoch into the TradeIntent
    broker adapter             -> refuse submission if the user's epoch has moved
    order already in flight    -> compensating CANCEL, then reconcile

State the guarantee honestly: *no order is submitted after a halt the submitter could observe, and
any order that lands regardless is immediately cancelled.* Not "atomic" — claiming atomicity across
a broker API would be false.

### 0.4 — Per-(user × symbol) state machines duplicate work and will not scale

NIFTY500 × 50 users = 25,000 state machines re-evaluated every 1m close. But impulse geometry,
pullback geometry and swing structure are **pure functions of price and volume** — they are not
user-specific. Only the *thresholds* differ.

**Change:** two tiers.

    SHARED   (once per symbol per candle)  StructureSnapshot: impulse, pullback,
                                           consolidation, swings
    PER-USER (cheap, lazily materialised)  threshold evaluation, state, setup

A `UserInstrumentStrategyState` is created only when a symbol first passes that user's discovery
filter, and evicted on `CLOSED`/`INVALIDATED` after cooldown. The spec's requirement — same symbol,
different state per user — is fully preserved; only the redundant geometry recomputation goes.

### 0.5 — `Instant.now()` must be banned outside the clock, or REPLAY is not deterministic

The spec puts `Instant.now()` in `TradeIntent` and implies it in transitions. Any direct call makes
replay non-reproducible, which silently destroys the value of §66.

**Change:** inject `java.time.Clock` everywhere. An ArchUnit test fails the build on `Instant.now()`,
`System.currentTimeMillis()` or `LocalDate.now()` outside `platform-core`. Replay supplies a
`TapeClock` driven by event timestamps. Event ordering must also be a deterministic total order
(timestamp, then sequence number) or two runs of the same tape will differ.

### 0.6 — Market-data staleness is per-instrument, not global

Test 20 ("market data stale → global halt") over-reacts: one illiquid symbol going quiet must not
stop the platform.

**Change:** staleness gates entries **for that symbol**. A global auto-halt fires only when a
configured fraction of the subscribed universe is stale at once, or the socket is down — i.e. on
evidence of feed failure rather than instrument quiet.

### 0.7 — Daily loss must be defined, and must latch

The spec does not say whether `maximumDailyLossPercent` is realised or unrealised. On unrealised
P&L, a position oscillating around the threshold will halt and resume repeatedly.

**Change:** `realised + unrealised`, evaluated on a timer rather than per tick, and **latched for
the session** once breached. No auto-resume.

### 0.8 — A slipped fill invalidates the risk calculation that authorised it

Quantity is sized from `(entry − stop)`. The structural stop is an absolute price level, so a fill
worse than the trigger silently raises risk-per-share, and a partial fill leaves a position sized
against a budget it no longer matches.

**Change:** recompute realised risk on every fill. If it exceeds the per-trade cap beyond a
configured tolerance, reduce to size or exit immediately, and write a `RiskEvent`.

### 0.9 — Corporate actions will manufacture fake top gainers

`(LTP − previousClose) / previousClose` renders a 1:5 split as −80% and a bonus as a crash.

**Change:** adjust `previousClose` from a corporate-actions calendar, and independently reject any
symbol whose computed day-change exceeds a sanity band (±25%) with reason
`SUSPECT_CORPORATE_ACTION` rather than trading it.

### 0.10 — The spread check needs depth the tick mode may not carry

`maximumSpreadPercent` needs bid/ask. Kite's LTP mode does not carry them; `quote`/`full` do, at a
higher subscription cost.

**Change:** subscribe the discovery universe in light mode, and **upgrade to full depth only for
symbols reaching `MOMENTUM_CANDIDATE`**. If depth is absent at trigger, deny with `DENIED_NO_DEPTH`
rather than assuming a spread.

### 0.11 — 21 modules is too many to start

Module boundaries are cheap to add later and expensive to get wrong now. Start with **8**; split
when a real cycle appears.

### 0.12 — Two sources of truth need an explicit ranking

JPA entities plus in-memory trading state will diverge. State it once and enforce it:

> **The broker is the truth. Postgres is the durable log. In-memory is a cache, rebuilt from
> Postgres + broker at startup.** Nothing trades until that rebuild reconciles.

### 0.13 — Multi-user self-competition is a real cost

Fifty users entering the same symbol on the same 1m close is one large order split into fifty market
impacts against each other.

**Change:** per-symbol entry jitter (0–2s, derived deterministically from `userId` so replay stays
stable), plus an admin metric for concurrent same-symbol entries. Flagged rather than solved — the
real answer may be netting, which is a much larger design.

### 0.14 — Smaller points

- `TradingPermissionResult` should return **all** failing reasons for audit, not just the first.
- Add a per-user, per-symbol **daily attempt cap**; a chopping stock will otherwise regenerate setups
  all session.
- Apply **hysteresis to rank-based discovery** (enter at top-20, leave at 25) or symbols will flap in
  and out at the boundary.
- Make `EntryPattern` an enum from day one so SHORT variants slot in without a schema change.
- `maximumOrderValue` and `maximumCapitalUtilizationPercent` must be checked against *pending* orders
  too, not just filled positions, or three simultaneous triggers can breach together.

---

## 1. Overall architecture

    +------------------- SHARED (one per platform) ---------------------+
    |  Broker feed -> MarketDataEngine -> TickBus                        |
    |                       |                |                           |
    |                       v                v  (ARMED symbols only)     |
    |                 CandleEngine 1m -> 3m/5m/15m                       |
    |                       |                                            |
    |                       v                                            |
    |                 IndicatorEngine   VWAP  ATR  RVOL  EMA             |
    |                       |                                            |
    |                       v                                            |
    |                 StructureEngine   impulse  pullback                |
    |                                   consolidation  swings            |
    |                       |                                            |
    |                       v                                            |
    |     SharedInstrumentState + StructureSnapshot                      |
    |     TopGainerEngine (ranking, hysteresis)                          |
    +-----------------------+--------------------------------------------+
                            |  fan-out: one evaluation per active user
         +------------------+------------------+
         v                  v                  v
    UserPipeline A     UserPipeline B     UserPipeline C
         |
         v   discovery filter (user thresholds)
    MomentumStateMachine -> MomentumSetup -> ARMED
         v
    EntryEngine -> TradeIntent (carries epoch)
         v
    RiskEngine -> sized intent          --+
         v                                 | all must pass
    TradingControlEngine -> EntryPermission-+
         v
    ExecutionEngine --(epoch-guarded)--> Broker(A)
         v
    PositionManager   <- SOLE owner of position lifecycle
         ^   ^   ^
         |   |   +-- EodExitJob          --+
         |   +------ RiskEngine             | submit ExitRequest;
         +---------- MomentumRideEngine   --+ never call the broker

## 2. Multi-user architecture

Each user gets a **single-threaded executor**. Every per-user transition is therefore serial by
construction: no locks inside user state, and no way for two threads to mutate one setup. Users run
in parallel; a slow or failing user cannot stall or corrupt another (bounded queue, timeout,
per-user circuit breaker).

                        SharedMarketState (immutable snapshots)
                                     |
        +----------------------------+----------------------------+
        v                            v                            v
    UserTradingContext A       UserTradingContext B       UserTradingContext C
      config / risk              config / risk              config / risk
      control state              control state              control state
      epoch (AtomicLong)         epoch                      epoch
      strategy state map         strategy state map         strategy state map
      broker session A           broker session B           broker session C

## 3. Module structure (8 to start)

    equity/
      platform-core   Clock, ids, event bus, Result types, ArchUnit rules
      domain          enums, records, state machines  (no Spring, no JPA)
      market          MarketDataEngine, CandleEngine, IndicatorEngine,
                      StructureEngine, TopGainerEngine, SharedInstrumentState
      strategy        discovery, MomentumStateMachine, detectors, EntryEngine
      trading         RiskEngine, TradingControlEngine, EmergencyHaltEngine,
                      ExecutionEngine, PositionManager, ExitEngine, reconciliation
      broker          BrokerPort + Kite adapter, session and secret handling
      persistence     JPA entities, repositories, Flyway
      app             Spring wiring, REST, WS/SSE, security, replay harness

`domain` carries no framework dependency. That is what lets identical code run under LIVE and
REPLAY, and what makes the state machine unit-testable with no Spring context.

## 4. Main domain objects

    record UserId(UUID value) {}
    record SetupId(UUID value) {}
    record TradeIntentId(UUID value) {}
    record ClientOrderId(String value) {}   // deterministic — the idempotency key

    record SharedInstrumentState(
        String symbol, double previousClose, double open, double dayHigh, double dayLow,
        double lastPrice, long cumulativeVolume, double vwap, double ema9, double ema20,
        double atr, double return1m, double return3m, double return5m, double return15m,
        double changeFromPreviousClose, double relativeVolume,
        double distanceFromDayHighPct, double distanceFromVwapPct,
        int currentGainerRank, int previousGainerRank,
        double niftyRelativeStrength, double sectorRelativeStrength, Instant lastUpdated) {}

    record StructureSnapshot(String symbol, Instant asOf,
        Optional<BullishImpulse> impulse, Optional<Pullback> pullback,
        Optional<Consolidation> consolidation, SwingSeries swings) {}

    record UserInstrumentStrategyState(UserId userId, String symbol, MomentumState state,
        Optional<MomentumSetup> activeSetup, Instant stateEnteredAt, String transitionReason) {}

    record MomentumSetup(SetupId setupId, UserId userId, String symbol, EntryPattern pattern,
        double triggerPrice, double structuralStop, double setupHigh, double setupLow,
        Instant armedAt, Instant expiresAt) {}

    record TradeIntent(TradeIntentId id, SetupId setupId, UserId userId, String symbol,
        Direction direction, EntryPattern pattern, double triggerPrice, double proposedStop,
        long epochAtCreation, Instant createdAt, String reason) {}

    record ExitRequest(UUID positionId, ExitReason reason, ExitPriority priority,
        String detail, Instant requestedAt) {}

## 5. Shared vs user-specific state

| Shared (per symbol) | Per user |
|---|---|
| ticks, candles 1m/3m/5m/15m | discovery pass/fail |
| VWAP, ATR, RVOL, EMA | `MomentumState` |
| returns 1m/3m/5m/15m | `MomentumSetup`, trigger, structural stop |
| gainer rank, previous rank | whether the geometry *qualifies* |
| impulse / pullback / consolidation geometry | position, orders, P&L |
| swing series | risk state, control state, epoch, cooldowns |
| NIFTY and sector returns | attempt counters |

Shared objects are **immutable snapshots** published by reference swap, so one user evaluation reads
a single consistent snapshot and can never observe a half-updated market.

## 6. Momentum state transition table

| From | Event | Mandatory conditions | Invalidation | To |
|---|---|---|---|---|
| IDLE | rank enters top-N | in top-N (hysteresis), liquidity OK, no corporate-action flag | — | MOMENTUM_DETECTED |
| MOMENTUM_DETECTED | 1m close | dayGain ≥ min, ret5m ≥ min, ret15m ≥ min, price > VWAP, RVOL ≥ min, distFromHigh ≤ max, outperforms NIFTY | any fails | MOMENTUM_CANDIDATE / IDLE |
| MOMENTUM_CANDIDATE | 1m close | `ImpulseDetector` confirms: move% ≥ min **and** move/ATR ≥ min, volume expanded | no impulse within N candles | IMPULSE |
| IMPULSE | 1m close | price retraces from impulse high, still > VWAP | retrace > max, VWAP lost, swing low broken | PULLBACK |
| IMPULSE | 1m close | range contracts, ≤ maxRangePct over ≥ minCandles | range too wide, VWAP lost | CONSOLIDATION |
| PULLBACK | 1m close | retrace within [min,max], pullbackVol < impulseVol, structure intact | retrace > max, VWAP lost, swing low broken, bearish expansion | ARMED |
| CONSOLIDATION | 1m close | tight range held, VWAP held, volume normalising | range break down, VWAP lost | ARMED |
| ARMED | **tick** | LTP > trigger, price > VWAP, spread OK, depth present, EntryPermission ALLOWED, RiskEngine approves | timeout, VWAP lost, structure broken | TRIGGERED |
| ARMED | clock | — | `armedTimeoutMinutes` elapsed → `SETUP_TIMEOUT` | INVALIDATED |
| TRIGGERED | intent created | epoch unchanged, no duplicate for (user, setup) | duplicate detected | ENTRY_ORDER_PENDING |
| ENTRY_ORDER_PENDING | broker fill | fill confirmed | rejection, cancel, timeout | POSITION_OPEN / INVALIDATED |
| POSITION_OPEN | 1m close | first higher-high + higher-low confirmed | stop hit | TRENDING |
| TRENDING | 1m close | HH/HL intact | weakening evidence | MOMENTUM_WEAKENING |
| MOMENTUM_WEAKENING | 1m close | structure still intact → tighten only | last HL broken → exit | TRENDING / EXIT_PENDING |
| any open | tick | — | stop hit, emergency, EOD | EXIT_PENDING |
| EXIT_PENDING | broker fill | flat confirmed | — | CLOSED |
| CLOSED | clock | — | — | COOLDOWN |
| COOLDOWN | clock | cooldown elapsed | — | IDLE |

Every transition writes `(userId, symbol, ts, from, to, reason, marketValues, setupId)`.

## 7. User trading-control state machine

    ACTIVE  --pause-->  TRADING_PAUSED  --resume-->  ACTIVE
      |                                                ^
      | halt (user / auto / admin)                     | explicit resume:
      v                                                | broker OK + data OK +
    HALTED ------------------------------------------- + reconciled + risk OK +
      |                                                  window OK + confirmation
      v disable
    DISABLED

`TRADING_PAUSED` blocks new entries only; existing positions manage normally. `HALTED` additionally
bumps the epoch and cancels pending entries. Resume is **never** automatic.

## 8. Emergency-halt state machine

    NORMAL --user or auto halt--> HALT_ACTIVE
                                    |
                          policy = MANAGE_EXISTING (default)
                                    |    positions keep their stops (0.1)
                                    |
                          policy = EXIT_ALL
                                    v
                          EXITING --all flat + reconciled--> HALTED_FLAT

Global halt is a separate, higher-priority flag: `GLOBAL_HALT` denies entry for every user and can
be released only by an ADMIN.

    Priority: GLOBAL_HALT > USER_HALT > USER_PAUSED > RISK > TIME_WINDOW > STRATEGY

## 9. Broker / order state machine

    CREATED -> SUBMITTING -> SUBMITTED -> PARTIALLY_FILLED -> FILLED
                   |             |               |
                   |             |               +-> CANCEL_PENDING -> CANCELLED
                   |             +-> REJECTED
                   +-> FAILED (network/timeout — state UNKNOWN until reconciled)

`FAILED` is not `REJECTED`. A timeout means the order may or may not exist at the broker; it must be
resolved by reconciliation, never assumed. Only `FILLED`/`PARTIALLY_FILLED` move position quantity.

## 10. Tick vs candle event flow

    tick   -> LTP, cumulative volume, staleness clock
           -> ARMED symbols only: trigger evaluation
           -> open positions: structural stop, emergency exit
    1m close -> indicators, structure, state machine, discovery, ranking
    3/5/15m  -> context only (aggregated from completed 1m)

Ticks never drive state transitions other than `ARMED -> TRIGGERED` and stop exits. That keeps the
strategy deterministic and replayable at candle granularity while keeping execution tick-precise.

## 11. Concurrency design

- One single-threaded executor per user; per-user state needs no locks.
- Shared state: immutable snapshots, `volatile` reference swap; readers never block writers.
- `ClientOrderId` is deterministic — `sha256(userId|setupId|direction|attempt)` — so a retry cannot
  create a second order.
- Entry submission holds a per-(user, symbol) guard so repeated trigger ticks yield one order.
- Epoch (`AtomicLong`) is the halt/submit interlock (0.3).
- Exit requests go to a per-position queue drained by `PositionManager` alone (0.2).

## 12. Tenant isolation

- `userId` on every user-owned row, with composite indexes leading on `user_id`.
- Repository layer takes `UserId` as a required parameter; a Spring Data base repository forbids
  unscoped finders on user-owned aggregates.
- `@PreAuthorize` on controllers plus a service-layer assertion that the authenticated principal
  matches the `userId` on the aggregate — never frontend filtering.
- Integration test asserts user A cannot read or mutate any of user B's entities through any REST
  path (test 27).

## 13. Broker reconciliation

Triggered on startup, reconnect, unexpected order state, manual request, and after emergency exit.

    fetch broker orders + positions for the account
    diff against local
      broker has position we do not      -> adopt as UNMANAGED, do not trail, alert
      we have position broker does not   -> mark PHANTOM, close locally, alert
      quantity mismatch                  -> broker wins, write ReconciliationEvent
      unknown local order                -> resolve by clientOrderId, else orphan + alert
    result: RECONCILED | MISMATCH

A user cannot enable entries until their own state is `RECONCILED`. A mismatch for user A does not
halt user B unless the failure is platform-wide.

## 14. Risk-engine interfaces

    interface RiskEngine {
        RiskDecision evaluateEntry(TradeIntent intent, UserTradingContext ctx, SharedInstrumentState mkt);
        void onFill(Position p, Fill f, UserTradingContext ctx);      // 0.8 re-check
        Optional<ExitRequest> evaluateOpenRisk(Position p, UserTradingContext ctx);
        DailyRiskState dailyState(UserId userId);
    }

    record RiskDecision(boolean approved, int quantity, double riskPerShare,
                        double totalRisk, List<String> denialReasons) {}

Sizing: `quantity = floor(allowedLoss / (entry - stop))`, then clamped by max order value, capital
utilisation, open positions, sector cap — each clamp recorded so the binding constraint is visible.

## 15. Final pre-order permission sequence

    0.  capture epoch                       (already on the intent)
    1.  global halt inactive
    2.  user ACTIVE
    3.  user emergency halt inactive
    4.  new entries enabled
    5.  market data fresh FOR THIS SYMBOL
    6.  broker connected
    7.  broker reconciled
    8.  inside trading window, before entry cutoff
    9.  risk approved (sizing + all caps incl. pending orders)
    10. setup still valid (not timed out, VWAP held, structure intact)
    11. no duplicate for (userId, setupId, symbol, direction)
    12. depth present, spread within limit
    13. acquire per-(user,symbol) submission guard
    14. RE-CHECK epoch — abort if moved
    15. submit with deterministic clientOrderId
    16. if epoch moved while in flight -> cancel + reconcile

## 16. Database schema (Postgres + Flyway)

    app_user(id, email, display_name, status, created_at)
    user_role(user_id, role)
    broker_account(id, user_id, broker, status, reconciled, last_connected_at, last_reconciled_at)
    broker_secret(broker_account_id, ciphertext, key_version, rotated_at)   -- envelope encrypted
    user_trading_configuration(user_id, ...)
    user_momentum_configuration(user_id, ...)
    user_risk_configuration(user_id, ...)
    user_trading_control(user_id, state, entries_enabled, emergency_halt, updated_at, reason)

    momentum_setup(id, user_id, symbol, pattern, trigger_price, structural_stop,
                   setup_high, setup_low, armed_at, expires_at, outcome)
    momentum_state_transition(id, user_id, symbol, ts, from_state, to_state, reason,
                              market_values_json, setup_id)
    rejected_opportunity(id, user_id, symbol, ts, stage, reason, values_json)
    trade_intent(id, user_id, setup_id, symbol, direction, pattern, trigger_price,
                 proposed_stop, epoch, created_at, reason)
    broker_order(id, user_id, broker_account_id, trade_intent_id, client_order_id UNIQUE,
                 broker_order_id, state, qty, filled_qty, avg_price, created_at, updated_at)
    order_event(id, order_id, ts, event_type, payload_json)
    position(id, user_id, symbol, direction, qty, avg_entry, initial_stop, current_stop,
             highest_price, lowest_price, realized_pnl, entry_time, exit_time,
             entry_pattern, exit_reason, state)
    position_event(id, position_id, ts, event_type, payload_json)
    risk_event(id, user_id, ts, type, detail_json)

    trading_control_event(id, actor_user_id, affected_user_id, action, previous_state,
                          new_state, reason, correlation_id, ts)
    broker_reconciliation(id, user_id, broker_account_id, ts, result, diff_json)
    audit_event(id, ts, actor_user_id, actor_role, affected_user_id, action,
                previous_state, new_state, reason, correlation_id)
    system_health_event(id, ts, component, status, detail)

Indexes: `(user_id, symbol)`, `(user_id, trading_date)`, `(user_id, status)`,
`(user_id, created_at)`, `(setup_id)`, `(trade_intent_id)`, unique `(client_order_id)`.

## 17. REST API

    POST   /api/auth/login
    GET    /api/me
    GET    /api/me/status
    GET    /api/me/config            PUT /api/me/config           (audited)
    GET    /api/me/risk              PUT /api/me/risk             (audited)
    POST   /api/me/controls/entries/enable | /pause
    POST   /api/me/controls/halt                                  (reason required)
    POST   /api/me/controls/resume                                (confirmation required)
    POST   /api/me/controls/exit-all                              (types EXIT ALL to confirm)
    GET    /api/me/positions | /orders | /setups | /rejections | /pnl
    GET    /api/market/board                                      (momentum board)
    GET    /api/market/instrument/{symbol}
    GET    /api/admin/users                                       (ADMIN)
    POST   /api/admin/halt | /resume                              (ADMIN, reason required)
    POST   /api/admin/users/{id}/halt | /resume                   (ADMIN)
    POST   /api/replay/start | /pause | /resume | /reset
    GET    /api/replay/state

## 18. WebSocket / SSE contracts

One authenticated socket per user; the server never sends another user's data on it.

    market.board        { symbol, ltp, dayPct, rank, prevRank, r1m, r3m, r5m, r15m,
                          rvol, vwapPos, distHigh, niftyRs, sectorRs, state, setup }
    strategy.transition { symbol, from, to, reason, ts }
    setup.armed         { symbol, setupId, trigger, stop, expiresAt }
    order.update        { orderId, state, filledQty, avgPrice }
    position.update     { positionId, symbol, qty, avgEntry, currentStop, unrealizedPnl, state }
    control.update      { entriesEnabled, halted, reason }
    system.banner       { globalHalt, reason, activatedAt }
    replay.clock        { simulatedTime, speed }

## 19. React component hierarchy

    App
      AuthGate
      AppShell
        HeaderBar          mode, market, broker, reconciliation, data, entries, halt, P&L
        ControlBar         ENABLE / PAUSE / EMERGENCY HALT / EMERGENCY EXIT ALL
        GlobalHaltBanner
        Routes
          DashboardPage
            MomentumBoard      sortable, filterable, no score column
            OpenPositionsPanel
            RejectedOpportunitiesPanel
          StockDetailPage
            InstrumentHeader
            MomentumStatePanel   current state + WHY
            StateFlowDiagram     IDLE -> ... -> COOLDOWN, current highlighted
            SetupPanel
            CandleChart          1m default; VWAP, EMA, impulse/pullback marks, stop
          SettingsPage        discovery / momentum / pullback / consolidation / risk / time
          HistoryPage
          AdminPage           user grid, halt/resume, global controls
          ReplayPage          date, universe, profile, speed, transport, REPLAY banner

## 20. Security and broker secrets

- Spring Security, short-lived JWT access + rotating refresh; roles `TRADER`, `ADMIN`.
- Broker credentials envelope-encrypted (AES-GCM, KMS-managed key, `key_version` for rotation) in
  `broker_secret`. Decrypted only inside the broker adapter, never returned by any API.
- A dedicated `SecretString` type whose `toString()` is redacted, so a stray log cannot leak it.
- Audit records never carry secret values, only references.
- All mutating control endpoints require a reason; emergency exit requires typed confirmation.

## 21. Failure scenarios and recovery

| Failure | Behaviour |
|---|---|
| Feed socket drops | reconnect with backoff; entries denied platform-wide while down; positions keep tick stops from last known + exit on EOD if unresolved |
| One symbol stale | entries denied for that symbol only (0.6) |
| Broker session expires | that user's entries disabled; exits still attempted; reconnect + reconcile required |
| Order timeout (`FAILED`) | state UNKNOWN; reconcile before any further action on that setup |
| Partial fill then rejection | position = filled qty only; risk recheck (0.8) |
| Phantom local position | reconciliation closes it locally and alerts |
| Unmanaged broker position | adopted read-only, never trailed, alerted |
| App restart with open positions | rebuild cache from DB + broker, reconcile, entries stay DISABLED until user enables |
| Postgres unavailable | trading halts globally (no durable log = no trading) |
| Risk service exception | fail closed — deny entry |

## 22. Test strategy

All 28 scenarios in §71 become named tests. Structure:

- **domain** — pure unit tests of the state machine table, one per row, no Spring.
- **strategy** — detector tests on synthetic candle series (impulse, pullback depth, VWAP loss,
  consolidation, timeout).
- **trading** — risk sizing and clamps; permission matrix; epoch race (test 11) driven by a latch
  that flips halt between check and submit.
- **concurrency** — two users, same trigger tick, independent outcomes (test 8); repeated trigger
  ticks produce one order (test 6).
- **broker** — fake broker adapter with programmable partial fill, rejection, timeout, disconnect.
- **persistence** — Testcontainers Postgres; tenant-isolation test (27).
- **replay** — golden tape; the same tape twice must produce byte-identical decisions
  (determinism guard for 0.5).

## 23. Recommended implementation sequence

Follows the spec's phases with two changes: the exit-ownership contract and the epoch land in
Phase 3 with execution rather than later, because retrofitting either is expensive.

1. **Phase 1** — modules, domain, clock, users/tenancy, auth model, market-data abstraction, candle
   engine (1m + aggregation), VWAP/ATR/RVOL, TopGainerEngine.
2. **Phase 2** — discovery, structure detectors, state machine, per-user state.
3. **Phase 3** — TradeIntent, RiskEngine, TradingControlEngine, **epoch**, execution, order state
   machine, reconciliation.
4. **Phase 4** — PositionManager as sole owner, **ExitRequest queue**, ride engine, structural
   trailing stop, weakening, EOD.
5. **Phase 5** — React dashboard, board, detail, chart, settings, controls.
6. **Phase 6** — admin, global halt, health, audit screens.
7. **Phase 7** — replay engine and UI, rejected-opportunity analytics.

---

## 24. Kite broker layer — as built

This section records what was actually implemented, and the four decisions that were not obvious
from the specification.

### 24.1 This engine owns its own Kite Connect app

A Kite application holds exactly **one** registered redirect URL. The options engine already owns
its registered URL on port 8089, so sharing one app would mean the two engines fighting over that
single setting, and whichever deployed last would silently break the login of the other. `equity`
therefore registers its own app; `equity.broker.kite.api-key` / `api-secret` come from the
environment and are never committed.

### 24.2 Tenant identity travels in `redirect_params`, with a nonce

Because the redirect URL cannot vary per user, the callback has to say who it belongs to. Kite
echoes `redirect_params` back verbatim, so the login URL carries `eq_user` and `eq_nonce`.

The nonce is the security half. Carrying only a user id would let anyone who can reach the callback
bind a request token to somebody else's account. `KiteAuthService` mints a single-use nonce per
login, scoped to one user and one time window, and refuses any callback that does not match — with
an identical message for every failure mode, so probing the endpoint reveals nothing.

### 24.3 One socket per user, but only one carries quotes

Kite delivers two unrelated things on the same WebSocket: **binary market-data frames**, identical
for everyone, and **text order postbacks**, which belong to whoever holds the access token. That
combination forces the shape:

- exactly one connection — the **primary** — holds the quote subscriptions and feeds the whole
  engine, satisfying the specification requirement that market data is shared;
- every other authenticated user holds a connection subscribed to *nothing*, purely to receive
  their own order updates;
- if the primary user's session ends, another authenticated user is promoted and the subscription
  set is replayed onto their socket. A shared feed that dies with one user's session is not shared.

Subscriptions are also replayed after any reconnect. A reconnect that comes back subscribed to
nothing is the worst failure mode available here: the socket is up, the health check is green, and
no instrument ever ticks again. There is a test for exactly that.

### 24.4 The adapter refuses REPLAY, but deliberately does **not** check `tradingEnabled`

`KiteBrokerAdapter` will not send anything while the engine is in REPLAY, or while the Kite
integration is disabled. Both checks sit at the last point before the wire rather than only in the
strategy, because a replay that reaches a live broker is not a bug you get to fix afterwards.

It does **not** consult the `tradingEnabled` kill switch. That switch stops new risk being taken;
exits must keep working while it is off. This is design note 0.1 — a halt that also blocks the exit
path converts a bad day into an unhedged position. Entry permission belongs one layer up, where the
difference between opening and closing a position is known.

### 24.5 Smaller decisions worth remembering

| Decision | Why |
| --- | --- |
| Every `BrokerPort` method takes a `UserId` | No ambient current user. The sibling options engine authenticated one client against a globally held primary token and answered for the wrong account until the 403s made it visible. |
| A timeout is reported as **not** retryable | The order may already be at the exchange. It is resolved by asking the broker what happened, never by resending. |
| Unrecognised order status maps to `UNKNOWN`, never a terminal state | Kite adds status strings over time; treating an unfamiliar one as COMPLETE or CANCELLED lets the position lifecycle act on a live order. |
| `fetchPositions` reads `net`, not `day` | `day` excludes anything carried in, and a square-off driven by it would leave that behind. |
| Order tags are validated against Kite's 20-character limit | Kite truncates silently, which breaks the match between a broker order and the intent that created it. |
| Instrument CSV is parsed with a quote-aware splitter | Company names contain commas; a naive split shifts tick size and lot size for exactly the large caps this engine trades. |
| `KiteRateLimiter` and the feed watchdog use `System.nanoTime()` | They measure real elapsed time on a socket. Under REPLAY the trading clock is tape time, which would make them fire at nonsensical moments. This is the one legitimate exception to the wall-clock ban, and it is not a business timestamp. |
| Nothing prints a token | `KiteSession`, `KiteCredentials` and `KiteProperties` all override `toString`, and `CredentialRedactionTest` enforces it. Spec section 37. |

### 24.6 What is deliberately not built yet

- **Session persistence.** `KiteSessionStore` is in memory. A Kite access token is worthless
  tomorrow, so persisting it buys only survival across a restart within the same session day, at the
  cost of writing a bearer credential to disk. Until that store is encrypted at rest with a key the
  application does not keep beside it, a mid-session restart should demand a fresh login, loudly.
- **Order-update persistence and reconciliation.** `OrderUpdateListener` exists and is fed; nothing
  consumes it yet. Push is an optimisation — a postback can be missed across a reconnect — so
  reconciliation against `fetchOrders` remains the authority when the trading layer is built.
- **Automatic mode upgrade to FULL for candidates** (design note 0.10). The transport supports it
  (`MarketDataPort.setMode`); the policy that decides which symbols are candidates belongs to the
  strategy layer.

### 24.7 Where credentials live

`src/main/resources/application.yml` is a **tracked file**. Anything written into it goes into git
history on the first commit and cannot be taken back out — rotating the key afterwards is the only
remedy, and only if you notice.

So the tracked file declares environment placeholders with empty defaults, and real values go in one
of two places:

```bash
# either the environment
KITE_ENABLED=true KITE_API_KEY=… KITE_API_SECRET=… mvn spring-boot:run
```

```yaml
# or ./config/application.yml — git-ignored, loaded automatically by Spring Boot with no flags
equity:
  broker:
    kite:
      api-key: …
      api-secret: …
```

`config/` is in `.gitignore` as a directory, not as one named file, so a second local override added
later is covered without anyone having to remember to add it.

---

## 25. The runtime pipeline — as built

Everything below is wired and running. The engine still ships disarmed (`REPLAY`,
`trading-enabled: false`, every user created with entries off), so wiring it did not arm it.

```
KiteTickerManager (MarketDataPort)
  └─ MarketDataRouter          freshness → candles → structure → tick consumers
       ├─ InstrumentFreshness  per-instrument staleness (0.6)
       ├─ CandleEngine         1m from ticks; 3/5/15m from completed 1m
       └─ StructureEngine      SharedInstrumentState, shared across users (0.4)
                                    ↓
                             UniverseService   rank, hysteresis, QUOTE→FULL promotion (0.10)
                                    ↓
                        per armed user:  MomentumStrategy   →  StrategySignal
                                    ↓
                             RiskEngine.authorise()         →  RiskDecision (sized)
                                    ↓
                        PositionLifecycle — the ONLY component that opens or closes (0.2)
                                    ↓
                             BrokerPort → Kite
```

### 25.1 Two paths, deliberately different in cost

| | driven by | does | must not |
| --- | --- | --- | --- |
| **Candle path** | completed 1m candle | advance the setup state machine, recompute indicators | act on a forming bar |
| **Tick path** | every tick, feed thread | check stops, check triggers | do any I/O |

Margin is the reason `MarginCache` exists: the risk check runs on the tick path, and an HTTP call
there would stall every instrument in the universe behind one user's margin lookup. A cached figure
is stale by construction, and that is the right direction to be wrong in — the exchange rejects an
order that exceeds real margin, whereas a blocked feed leaves every stop unevaluated.

### 25.2 The strategy is conditions, not a score

`MomentumStrategy` is long-only continuation: strongly up on the day, leading the index, thrust on
volume, a pause that does not give the thrust back, then resumption. Three gates in order —
**mandatory** (must hold at every moment), **setup** (IDLE → IMPULSE → PULLBACK/CONSOLIDATION →
ARMED, candle-driven), **trigger** (ARMED → TRIGGERED, tick-driven).

No weighted score, per the specification and for a good reason: a weighted sum lets a strong reading
on one axis buy a failing reading on another, so a trade gets taken for reasons nobody chose. Every
rejection names its condition and is counted in `RejectionLog`, which is what makes the counts
interpretable — README principle 2, and the reason a sibling engine's run-up gate went unnoticed for
months while it rejected the best candidates.

SHORT is absent rather than flagged off. Every comparison is written for the long side; a flag
claiming SHORT support while the arithmetic assumes upward moves would be worse than an honest gap.

### 25.3 Safety invariants now enforced by tests

| Invariant | Where | Test |
| --- | --- | --- |
| The stop wins over the target on the same tick | `ExitReason.priority()` | `ExitQueueTest` |
| A halt outranks even the stop | same | `ExitQueueTest` |
| One position can only be queued for exit once | `ExitQueue` | `ExitQueueTest` |
| Exits keep working while a user is halted (0.1) | `PositionLifecycle` | `PositionLifecycleTest` |
| An entry authorised before a halt is not sent after it (0.3) | `PositionLifecycle.open` | `PositionLifecycleTest` |
| A fill arriving after a halt is closed, not adopted (0.3) | `applyEntryUpdate` | `PositionLifecycleTest` |
| An exit that could not be sent is requeued, never dropped | `drainExits` | `PositionLifecycleTest` |
| A rejected exit returns the position to OPEN so it retries | `applyExitUpdate` | `PositionLifecycleTest` |
| Orders match on tag, never on symbol | `matchesTag` | `PositionLifecycleTest` |
| Daily loss counts unrealised and latches (0.7) | `AccountLedger` | `AccountLedgerTest` |
| A latch does not clear when the market recovers | same | `AccountLedgerTest` |
| No depth means no entry, not a zero spread (0.10) | `RiskEngine` | `RiskEngineTest` |
| Unknown margin reads as zero, not unlimited | `RiskEngine` | `RiskEngineTest` |
| A slipped fill can exceed the budget that approved it (0.8) | `fillStillWithinBudget` | `RiskEngineTest` |
| Only the lifecycle may call `placeOrder` (0.2) | ArchUnit | `ArchitectureRulesTest` |

### 25.4 The universe is the NIFTY 50, and all of it is evaluated

`UniverseProperties` holds the NIFTY 50 constituents. Index membership is **not** in the broker's
instrument master — that file carries tokens, symbols and lot sizes, not which stocks belong to
which index — so there is nothing to derive it from at runtime and it has to be stated. NSE
rebalances, so the list goes stale: override `equity.universe.symbols` rather than editing the
default, and check `/api/universe` for `unresolved`, which reports any configured symbol with no
instrument token instead of letting it present as a stock that simply never trades.

**Every stock in the universe is evaluated** (`evaluate-all`, default on), and all of them are
subscribed in FULL from the start. Eligibility is decided entirely by the strategy's named
conditions.

The ranked discovery set still exists for a universe large enough that FULL depth on all of it would
be wasteful, but it is a **bandwidth device and nothing more**. It had quietly become a second,
unexamined entry filter: a stock meeting every condition in the strategy would be skipped because
nineteen others happened to be up more that minute — and nothing in the strategy reads the rank at
all. Rank is still computed and carried on `SharedInstrumentState` for display and later analysis.

### 25.5 Still not built

- **Persistence.** Positions, orders, rejections and the ledger are all in memory. A restart loses
  the session's record and, more seriously, its view of what is open — `Reconciler` against
  `fetchOrders`/`fetchPositions` is the next thing to build, and it is what makes a restart safe.
- **REPLAY event source.** The clock abstraction and the ban on `Instant.now()` are in place, but
  nothing reads a tape yet, so REPLAY currently means "will not trade" rather than "replays".
- **Relative volume against a historical baseline.** `StructureEngine` computes it within the
  session only, and says so in its own comment.
- **Sector relative strength.** Always 0 — there is no sector map.


### 25.6 Broker rules learned from a live order, 2026-09-04

A one-share connectivity test against the real account, since removed. Each rejection below was a
rule no unit test could have found, and the first two would have broken **every** order the engine
sends — including exits.

| Kite said | Meaning |
| --- | --- |
| "Intraday orders (MIS) are allowed only till 3:12 PM" | MIS has a cutoff earlier than the session close. |
| "Market orders without market protection are not allowed via API" | **Every entry and every exit would have been rejected.** `market_protection` is now sent on all MARKET orders, defaulting to 3%. |
| "Your order could not be converted to a After Market Order (AMO)" | A regular order is not silently converted after hours; AMO is a separate endpoint, `/orders/amo`. |
| *(accepted, order id returned)* | An order id is a **receipt, not a confirmation**. Kite issues it on receipt and the order can still be rejected in validation afterwards — only the order book settles it. |

That last point is the one with a standing consequence. `PositionLifecycle` creates a
`PENDING_ENTRY` position from the returned order id; if the order is rejected afterwards and the
postback is missed, that position stays `PENDING_ENTRY` indefinitely and the engine believes it has
a working entry that does not exist. **The reconciler against `fetchOrders`/`fetchPositions` is what
closes this, and it is still not built** — it is the highest-priority remaining item.


## 26. Stop, target and size — as built

### 26.1 The three numbers

| | how it is set | adaptive? |
| --- | --- | --- |
| **Stop** | the **wider** of the pause low (where the setup is proven wrong) and `stopAtrMultiple × ATR` (the instrument's own noise) | widens only, at entry |
| **Target** | `entry + (entry − stop) × targetRMultiple`, default **2R** | derived from the actual stop |
| **Quantity** | `riskPerTrade ÷ (entry − stop)`, capped by notional and margin | **yes — the adaptive part** |

Sizing is the piece doing the real work: every trade risks the same ₹1,000 whatever the stock or the
stop width, so a tight setup buys a large position and a wide one a small position. The notional cap
exists because a very tight stop would otherwise buy the whole account.

The ATR floor matters because `minStopPercent` is volatility-blind: 0.25% is generous on one stock
and inside the spread on another. The floor can only **widen** a stop, which reduces size — the
rupee risk is unchanged and the chance of being shaken out by ordinary jitter goes down.

Because the target is a multiple of the risk actually taken, a stop widened by the floor moves the
target out with it. Deriving the target from the structure low instead would promise a 2R target on
a trade whose R is larger than that.

### 26.2 After entry: `ExitPolicy`, per user, everything off by default

`ExitPolicy.fixed()` is what ships — the stop and target set at entry never move, and a position has
four ways out: hard stop, target, time stop, square-off.

Three options exist and are **off**:

| option | what it does |
| --- | --- |
| `breakevenEnabled` | stop to the fill exactly once the trade reaches `breakevenArmAtR` |
| `trailingEnabled` | follow the high-water mark at `trailingAtrMultiple × ATR`, armed at `trailingArmAtR` |
| `structureExitEnabled` | close on a **completed** 1m bar below VWAP — raises `ExitReason.STRUCTURE` |

Off by default is an admission, not a recommendation: none of them has been measured on this
market's tape, and a trailing stop that has not been measured is a preference. They are per user and
switchable at runtime (`POST /api/users/{id}/exit-policy`) precisely so one user can run them against
another taking the same signals, and the difference is attributable to the exit alone.

That framing comes from the sibling options engine, where the exit was the part that gave back what
the entries earned — and where the obvious fixes mostly failed: cutting losers early clipped winners,
holding through the stop recovered 76% of the time but doubled the tail. Nothing here should be left
on because it sounds right.

### 26.3 The invariant that makes it safe

**A stop only ever moves in the favourable direction.** Every path goes through
`Position.withStop`, which refuses to loosen. That is what lets the two policies compose without
ordering rules — whichever asks for the tighter level wins, and neither can undo the other — and it
is why switching trailing on mid-position cannot widen a stop already in force.

The high-water mark lives on the `Position` rather than in the trailing logic, so progress is
measured from the best price the trade has seen, not from wherever price is when the policy is
switched on. Without that, "reached 1R" would stop being true after a pullback and trailing would
arm and disarm as price oscillates.

A missing or zero ATR **disables** trailing rather than substituting a default width: a trailing stop
with an invented distance looks calibrated and is not.

### 26.4 Structure exit is candle-driven, not tick-driven

VWAP is crossed and re-crossed constantly inside a minute. Acting on that would exit on noise; a
completed bar closing below it is a different statement. It is also the exit most likely to clip a
winner, which is why it is off by default and needs its own evidence before it stays on.

### 26.5 Where each setting is applied

| setting | applied by | when |
| --- | --- | --- |
| minDayChange, maxDayChange, sanityBand, maxDistanceFromHigh | `MomentumStrategy.checkMandatory` | every completed 1m candle |
| requireAboveVwap, requireEmaStack, requireOutperformIndex | `MomentumStrategy.checkMandatory` | every completed 1m candle |
| minImpulseReturn5m, minRelativeVolume | `tryImpulse` | candle |
| min/maxPullback, maxConsolidationRange, minConsolidationBars | `tryPause` / `tryArm` | candle |
| triggerBuffer | `tryArm` | candle |
| **maxDayChange, sanityBand, requireAboveVwap (again)** | **`recheckAtTrigger`** | **the trigger tick** |
| stopAtrMultiple, targetRMultiple | `MomentumStrategy.onTick` | the trigger tick |
| entryWindow start/end | `checkMandatory` **and** `RiskEngine` | candle, then again at entry |
| every `RiskLimits` field | `RiskEngine.authorise` | at entry |
| `ExitPolicy` | `StopAdjuster` / structure check | per tick / per candle |
| timeStop, squareOff | `SessionOrchestrator` | scheduled |

### 26.6 The gap between arming and triggering

A setup is armed at a candle close and fires on a tick up to a minute later. In that window only
some of the shared state has moved: `lastPrice`, `dayHigh` and `dayLow` update on every tick, while
VWAP, the EMAs, relative strength and the returns are recomputed only on a completed bar.

So three mandatory conditions could go stale, and **one of them moves in the dangerous direction**:
the breakout that triggers the entry is itself pushing the day change up, so a stock can cross from
acceptable to over-extended in the very move being joined. It would then be entered on a condition
that was true a minute ago and is false now.

`recheckAtTrigger` re-evaluates exactly the price-derived ones — day change against both bounds, the
corporate-action band, and above-VWAP. The candle-derived conditions are deliberately not re-tested:
they are identical to what the candle path already checked, so it would be work that cannot change
the answer.

Those rejections are named separately (`dayChangeExtendedAtTrigger`, `belowVwapAtTrigger`,
`suspectCorporateActionAtTrigger`) because "failed a minute after it passed" is a different fact from
"never passed", and collapsing them would hide how often a setup goes stale before it fires.
