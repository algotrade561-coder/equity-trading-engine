package com.equity.api;

import com.equity.app.EngineProperties;
import com.equity.broker.kite.KiteProperties;
import com.equity.broker.kite.KiteTickerManager;
import com.equity.market.MarketDataRouter;
import com.equity.market.universe.UniverseService;
import com.equity.trading.PositionBook;
import com.equity.user.UserRegistry;
import com.equity.platform.time.TradingClock;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Engine status for the UI header. Read-only — nothing here mutates engine state. */
@RestController
@RequestMapping("/api/status")
public class StatusController {

    private static final LocalTime OPEN = LocalTime.of(9, 15);
    private static final LocalTime CLOSE = LocalTime.of(15, 30);

    private final EngineProperties props;
    private final TradingClock clock;
    private final KiteProperties kite;
    private final KiteTickerManager tickers;
    private final com.equity.broker.kite.KiteSessionStore sessions;
    private final com.equity.broker.kite.KiteCredentialsProvider credentials;
    private final com.equity.platform.security.CurrentUser currentUser;
    private final MarketDataRouter router;
    private final UniverseService universe;
    private final UserRegistry users;
    private final PositionBook positions;

    public StatusController(EngineProperties props, TradingClock clock, KiteProperties kite,
                            KiteTickerManager tickers,
                            com.equity.broker.kite.KiteSessionStore sessions,
                            com.equity.broker.kite.KiteCredentialsProvider credentials,
                            com.equity.platform.security.CurrentUser currentUser,
                            MarketDataRouter router,
                            UniverseService universe, UserRegistry users, PositionBook positions) {
        this.props = props;
        this.clock = clock;
        this.kite = kite;
        this.sessions = sessions;
        this.credentials = credentials;
        this.currentUser = currentUser;
        this.tickers = tickers;
        this.router = router;
        this.universe = universe;
        this.users = users;
        this.positions = positions;
    }

    @GetMapping
    public Map<String, Object> status() {
        // Configured, connected and trading are three different things, and the checklist should not
        // blur them: an unconfigured broker is a deployment gap, a configured one with no session is
        // a login the operator still owes us.
        // "Configured" must mean what the engine actually resolves at call time, not what is in
        // the config file. Credentials moved to a per-user encrypted store and the application-wide
        // pair is now empty by design, so asking KiteProperties reported NOT_CONFIGURED forever —
        // and because it was tested before connection state, it hid whether a login was even owed.
        String broker = !kite.isEnabled() ? "DISABLED"
                : tickers.isConnected() ? "CONNECTED"
                : !hasCredentials() ? "NOT_CONFIGURED"
                : hasSessionToday() ? "AWAITING_FEED" : "AWAITING_LOGIN";

        LocalTime now = clock.timeOfDay();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "EquityEngine");
        m.put("version", "0.1.0-SNAPSHOT");
        m.put("mode", props.getMode());
        m.put("tradingEnabled", props.isTradingEnabled());
        m.put("marketOpen", !now.isBefore(OPEN) && !now.isAfter(CLOSE));
        m.put("serverTimeIst", now.withNano(0).toString());
        m.put("tradingDate", clock.tradingDate().toString());
        m.put("armedUsers", users.armed().size());
        m.put("totalUsers", users.size());
        m.put("subscribedSymbols", universe.subscribedSymbols().size());
        m.put("discoverySetSize", universe.discoverySet().size());
        m.put("ticksSeen", router.ticksSeen());
        m.put("openPositions", positions.all().stream().filter(p -> p.hasExposure()).count());
        m.put("components", components(broker));
        m.put("componentStates", Map.of(
                "RUNNING", "live in the tick or request path",
                "IDLE_NO_DATA", "wired, but no market data has reached it yet",
                "NO_ARMED_USER", "wired, but nobody is permitted to trade so it never acts",
                "SHADOW_ONLY", "evaluating and recording, but nobody is armed so nothing is sent"));
        return m;
    }

    /** Whether the signed-in user has an api key and secret the engine can actually resolve. */
    private boolean hasCredentials() {
        return currentUser.id().map(id -> credentials.find(id).isPresent())
                .orElseGet(() -> kite.isConfigured());
    }

    /**
     * A stored session valid for today. Distinguishes "log in" from "the feed has not come up yet",
     * which are different problems with different fixes.
     */
    private boolean hasSessionToday() {
        return currentUser.id().map(sessions::isAuthenticated).orElse(false);
    }

    /**
     * The readiness checklist.
     *
     * <p>{@code BUILT_IDLE} is a state this deliberately distinguishes from running. `CandleEngine`
     * and `TopGainerEngine` exist and pass their tests, but neither is a Spring bean and no tick
     * reaches either of them — calling that "wired", as this endpoint previously did, is the kind of
     * green light that hides an entire missing layer. A checklist that overstates readiness is worse
     * than no checklist.</p>
     */
    private Map<String, Object> components(String broker) {
        boolean streaming = tickers.isConnected()
                && tickers.subscribedSymbolCount() > 0
                && tickers.tickListenerCount() > 0;
        boolean ticking = router.ticksSeen() > 0;
        long armedUsers = users.armed().size();

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("broker", broker);
        c.put("marketData", streaming ? "RUNNING"
                : tickers.isConnected() ? "CONNECTED_NO_CONSUMER" : "NOT_STREAMING");
        // These are fed by the router, so they are running exactly when data is arriving. Reporting
        // them as ready before a single tick has landed is the overstatement this endpoint is
        // supposed to have stopped making.
        c.put("candleEngine", ticking ? "RUNNING" : "IDLE_NO_DATA");
        c.put("topGainer", universe.discoverySet().isEmpty() ? "IDLE_NO_DATA" : "RUNNING");
        c.put("strategy", ticking ? "RUNNING" : "IDLE_NO_DATA");
        // Evaluation runs for every active user; only an armed one can trade. Reporting SHADOW
        // rather than RUNNING makes the difference visible at a glance.
        // Risk and execution are wired, but they only ever act for an armed user. Saying RUNNING
        // with nobody armed would imply the engine could trade, and it cannot.
        c.put("risk", armedUsers > 0 ? "RUNNING" : (ticking ? "SHADOW_ONLY" : "NO_ARMED_USER"));
        c.put("execution", armedUsers > 0 ? "RUNNING" : "NO_ARMED_USER");
        return c;
    }
}
