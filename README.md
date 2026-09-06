# equity-trading-engine

Intraday **equity** engine for NSE cash + stock futures. Separate project, separate process,
separate port from the options engine — they share no code and no database.

## The one architectural rule

A signal never names an instrument.

```
        RELIANCE
   ┌───────┴───────┐
 CASH            FUTURE
   └───────┬───────┘
     UNDERLYING STATE      ← InstrumentPair (cash + futures linked)
           ↓
     Setup / Trigger
           ↓
      TradeIntent          ← "RELIANCE LONG". No instrument, no order.
           ↓
    Execution Router       ← prices liquidity, spread, margin, charges
   ┌───────┼───────┐
 CASH    FUTURE   OPTION
```

The decision engine forms a view on the **underlying** from cash and futures together and emits a
`TradeIntent`. A separate execution layer decides how to express it. That boundary is the design's
main safeguard: exactly one component owns a position's lifecycle. The sibling options engine never
made this separation, and five different code paths ended up closing each other's positions —
29% of one strategy's trades were exited by a path that did not own them.

## Status — thin vertical slice

Boots, serves an API, serves a console page. Each subsystem gets added one at a time with its own
kill switch.

| component    | notes                                                                    |
|--------------|--------------------------------------------------------------------------|
| broker       | Kite auth, orders, positions, margin, streaming — `docs/DESIGN.md` §24    |
| marketData   | shared feed → freshness → candles → structure                            |
| candleEngine | 1m from ticks, 3/5/15m from completed 1m                                 |
| topGainer    | ranking with hysteresis, QUOTE→FULL promotion                            |
| strategy     | long-only continuation, deterministic conditions — `docs/DESIGN.md` §25   |
| risk         | per-user sizing, daily-loss latch, named denials                         |
| execution    | single position-lifecycle owner, prioritised exit queue                  |
| persistence  | **not built** — everything is in memory, a restart loses the session      |
| REPLAY       | **not built** — the clock seam exists, nothing reads a tape yet           |

`/api/status` reports each of these as `RUNNING`, `IDLE_NO_DATA` (wired, no data yet) or
`NO_ARMED_USER` (wired, nobody permitted to trade). It deliberately does not report readiness it
cannot demonstrate.

**Three switches must all be on before an order can be sent**, and they are independent on purpose:

1. `engine.mode: LIVE` — ships as `REPLAY`
2. `engine.trading-enabled: true` — ships `false`
3. per user, **Arm entries** in the console — every user is created disarmed

None of the three affects exits. A halted, disarmed or loss-latched user still has their positions
managed and closed (design note 0.1).

## Run

```bash
mvn package        # builds the React console into the jar
java -jar target/equity.jar
```

Open <http://localhost:8090>. One artefact: the jar serves the API and the console, so a front end
built from a different commit than the back end is not a thing that can happen.

Iterating on Java only: `mvn package -Dskip.ui=true`.
Iterating on the console: `cd ui && npm run dev` — port 5173, proxying `/api` to the running engine,
with hot reload.

### Where the console goes

Vite builds into **`src/main/resources/static`**, which is git-ignored.

Generated output in the source tree looks like the wrong choice, and `target/classes/static` was
tried first for exactly that reason. It was worse: `target/classes` is written by anything that
compiles, and an IDE build does that without ever running the Maven phase that produces the console.
That gave a packaged jar which worked and an IDE run which returned 404 for every page — three
times, with nothing in the logs to explain it. Output that only exists when built one particular way
is output that will go missing.

As a plain resource it survives every path: `mvn compile`, `mvn test`, `mvn package`,
`spring-boot:run`, and an IDE build all copy it into `target/classes` like any other file. The npm
build runs at `generate-resources`, just before that copy.

If `/` ever 404s again, the console was not built: run `cd ui && npm run build`, or any Maven goal
from `compile` upward.

## Configuration

Nothing secret is in the repository. `./config/application.yml` is git-ignored and loaded
automatically by Spring Boot; environment variables work too.

**Broker credentials do not belong in that file.** They are entered on the settings page and stored
encrypted, per user, in the database — a per-user key also means two people are not sharing one Kite
app. The `equity.broker.kite.api-key` / `api-secret` properties exist only as an application-wide
fallback and are deliberately left unset.

Two things cannot move into the database, because both are needed *before* the database can be read:

| stays in the file | why |
|---|---|
| `EQUITY_SECRET_KEY` | it is what decrypts the credential rows — storing it beside them would defeat the point |
| `GOOGLE_CLIENT_ID` / `SECRET` | needed to sign in, and nothing can be read from the database until somebody has |

| variable | purpose |
|---|---|
| `EQUITY_SECRET_KEY` | encrypts broker credentials and tokens at rest. Unset, the engine runs but **declines to store them** rather than writing plaintext |
| `GOOGLE_AUTH_ENABLED` | `true` requires sign-in. **Off means every endpoint is open** — set it before this is reachable beyond localhost |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | the OAuth client. With auth on and these unset the engine refuses to start rather than starting open |
| `EQUITY_SEED_EMAIL` | created as ADMIN at startup if absent. Without a seed row nobody can sign in — the allow-list is the gate |
| `KITE_ENABLED`, `KITE_API_KEY`, `KITE_API_SECRET` | application-wide broker fallback; per-user credentials from the settings page take precedence |

## Sign-in

Google proves identity; the `app_user` table grants access. Anyone with a Google account can
authenticate, so authentication alone would open an order-placing application to the internet.

`SeedUserInitialiser` creates the seed account **at startup** from `equity.auth.seed-email`. There is
no bootstrap-on-first-login: admitting the first caller into an empty table would leave a window
between deployment and first login that anyone reaching the callback could win. `GoogleUserService`
therefore only ever *reads* the table — no sign-in path can grant itself access.

The seeder is idempotent and never modifies an existing row: an account that has since been disabled
or demoted stays that way across a restart, because that was somebody's decision.

### Registering the redirect URI

This engine shares the options engine's Google OAuth client, but **not** its callback URL. That
engine runs on `8089` under the context path `/advalgotrade`, so what is registered is:

```
http://localhost:8089/advalgotrade/login/oauth2/code/google      # the options engine
http://localhost:8090/login/oauth2/code/google                   # this engine — must be ADDED
```

Google matches redirect URIs exactly — no wildcards, no prefix matching — and answers an
unregistered one with **"Access blocked: This app's request is invalid"** without saying which URI it
expected. Add the second line under *Google Cloud console → APIs & Services → Credentials → your
OAuth 2.0 Client → Authorised redirect URIs*. The first line stays; a client may hold several.

The engine prints the exact string it will send at every start, so this never has to be guessed:

```
GoogleClientRegistration : Google sign-in ready. This callback MUST be an authorised redirect URI
on the OAuth client, character for character: http://localhost:8090/login/oauth2/code/google
```

**If the block persists after adding it**, the cause is the other one: an OAuth consent screen still
in *Testing* admits only listed test users, and the seed address has to be added under *Audience →
Test users*. That failure reads differently — it names the app rather than calling the request
invalid.

## Inspecting the database

The H2 console is **off by default** and has to be turned on deliberately — it is a full SQL shell
over live positions, the daily loss latch and the encrypted credential rows, not a viewer.

```yaml
# config/application.yml
spring:
  h2:
    console:
      enabled: true
```

Then <http://localhost:8090/h2-console>:

```
JDBC URL : jdbc:h2:file:./data/equity;MODE=PostgreSQL;AUTO_SERVER=TRUE
User     : sa
Password : (blank)
```

`AUTO_SERVER=TRUE` is what lets the console attach while the engine is running — the same file, one
writer, no need to stop trading to look at a table.

Two guards, because "signed in" is not the right bar for a SQL shell:

- **ADMIN only** when sign-in is on. A trader who may arm their own account has no business editing
  the table that records what everyone holds.
- `web-allow-others: false` — refuses non-local connections whatever the security config says.

## What survives a restart

H2 in file mode at `./data/equity`, `MODE=PostgreSQL` so the move to a real Postgres is a URL change
rather than a rewrite.

| | why it matters |
|---|---|
| **Daily loss latch** | a latch a restart clears is not a latch — restarting is exactly what someone would do while trying to fix whatever was going wrong |
| **Open positions** | otherwise the engine returns believing it holds nothing while the broker still holds shares, with no stop and no square-off |
| **Kite session** | encrypted; a mid-session restart no longer drops the feed and the login |
| **Broker credentials** | encrypted, per user, entered on the settings page |
| **Risk limits, thresholds, exit policy** | per user |

**Entry permission is deliberately not restored.** Arming is a decision about right now; a process
that came back armed because it was armed yesterday is the one surprise nobody wants.

## Logging in to Kite

This engine uses **its own** Kite Connect app. A Kite app holds exactly one registered redirect URL,
so sharing one with the options engine would mean whichever deployed last silently broke the login
of the other.

1. Create an app at <https://developers.kite.trade>.
2. Register this redirect URL against it, character for character:

   ```
   http://localhost:8090/api/broker/kite/callback
   ```

   The console page shows the value the server actually expects, with a copy button. A mismatch here
   is the most common setup failure, and the error Zerodha shows for it does not say what it wanted.
3. Start the engine with the credentials in the environment — never in a file:

   ```bash
   KITE_ENABLED=true KITE_API_KEY=xxx KITE_API_SECRET=yyy mvn spring-boot:run
   ```
4. Open <http://localhost:8090>, pick or generate a user id, and press **Connect to Zerodha**. After
   the Zerodha login you land back on the console with the session shown.

Tokens are invalidated by Kite every morning, so this is a once-per-trading-day step per user. The
session is held in memory only: a restart during market hours demands a fresh login, deliberately —
see `docs/DESIGN.md` §24.6.

**The browser never receives a token.** It learns whether a user is connected and which Zerodha
client code they are connected as, and nothing else (spec §37).

### Endpoints behind the page

| method | path | purpose |
|---|---|---|
| GET  | `/api/status`                   | mode, kill switch, subsystem checklist |
| GET  | `/api/broker/kite/config`       | whether the app is configured, and the redirect URL to register |
| GET  | `/api/broker/kite/login-url`    | `?userId=` → the Zerodha URL to open |
| GET  | `/api/broker/kite/callback`     | where Zerodha returns; validates a single-use nonce |
| GET  | `/api/broker/kite/session`      | `?userId=` → connected / client code / feed state |
| POST | `/api/broker/kite/logout`       | `?userId=` → invalidates at the broker and locally |

### Trading endpoints

| method | path | purpose |
|---|---|---|
| GET  | `/api/users`                          | every user with P&L, limits and switches |
| POST | `/api/users/{id}/entries?enabled=`    | arm or disarm entries (routine) |
| POST | `/api/users/{id}/halt`                | emergency stop: bumps the epoch, closes everything |
| POST | `/api/users/{id}/resume`              | clear a halt; entries stay disarmed |
| POST | `/api/users/{id}/flatten`             | close everything without stopping the user |
| POST | `/api/users/{id}/clear-loss-latch`    | override the daily-loss latch (logged loudly) |
| GET  | `/api/positions?userId=`              | live and closed positions with P&L |
| GET  | `/api/rejections`                     | why candidates did not become trades |
| GET  | `/api/universe`                       | subscribed symbols, discovery set, tick counts |
| GET  | `/api/setups?userId=`                 | what the strategy currently believes per symbol |

## Principles carried over from the options engine

These are paid-for lessons, not preferences.

1. **Everything that can place an order ships OFF.** `engine.trading-enabled: false` is the master
   switch. A feature defaulting to ON is a feature nobody decided to run.
2. **Capture before you trade.** Record every fact *and every gate rejection* first. Gates get
   chosen from our own data, not from priors. Without rejection logging you cannot tell a gate that
   filters noise from one that blocks your best candidates — we measured one doing exactly that.
3. **OI is captured as a fact, not interpreted.** A 70%-accurate OI-direction forecaster scored
   coin-flip on forward price there. Price↑/OI↑ = "long build-up" must be measured on stock-futures
   tape before it gates anything.
4. **Key instrument series on the trading symbol**, never `(strike, optionType)` or bare underlying.
   Two live expiries under one key produced +624%-in-one-second fills and invalidated a 16-day
   backtest.
5. **Every result reports concentration and leave-one-day-out.** Every positive on that corpus
   dissolved without a handful of trades.
6. **Model the lifecycle, not a chain of booleans.** A 7-condition AND collapses trade count and
   hides which condition did the work.

## What the engine sends

One order shape, always — `PositionLifecycle` is the only component that places an order and it does
not take a choice:

| field | value | why |
|---|---|---|
| `product` | `MIS` | intraday; everything opened is squared off the same session |
| `variety` | `regular` | an after-market entry would open a position with nobody watching it |
| `order_type` | `MARKET` | an unfilled exit is an unbounded loss |
| `market_protection` | `3%` | **Kite refuses API market orders without it** — every entry *and every exit* would be rejected. Configurable via `equity.broker.kite.market-protection-percent`; `0` omits the field and reproduces the refusal. |
| `tag` | `e<user8>-<seq>` | 20 chars, Kite's limit; how a fill is matched back to the intent that caused it |

Exits use whatever product the position holds, not a constant: squaring a CNC holding off with an
MIS sell does not close it, it opens an intraday short alongside it. Exits are always `regular`.

These are pinned by tests in `PositionLifecycleTest` and `KiteOrderMapperTest`, not by convention.
