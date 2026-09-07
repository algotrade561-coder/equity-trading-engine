package com.equity.app;

import com.equity.broker.kite.KiteInstrumentMaster;
import com.equity.broker.kite.KiteProperties;
import com.equity.broker.kite.KiteSessionStore;
import com.equity.broker.kite.KiteTickerManager;
import com.equity.domain.user.UserId;
import com.equity.user.UserRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Brings the market data feed back after a restart, without a human signing in again.
 *
 * <h2>Why</h2>
 * <p>A Kite access token is issued for a trading date and is stored, encrypted, so it survives the
 * process. {@link KiteSessionStore#restorableUsers()} was written for exactly this and its own
 * comment says so — "used at startup to bring the feed back without a human signing in again" — but
 * nothing called it. The consequence was quiet and expensive: after any restart the session was
 * still valid, the console still reported the user as logged in, and the feed simply never came up.
 * Nothing was subscribed, no candle was built, and no stop was evaluated for any open position,
 * until somebody noticed and clicked Connect.</p>
 *
 * <p>Restarting mid-session is not an exotic case. It is what happens after a configuration change,
 * and it is the moment the engine is most likely to be holding a position that needs watching.</p>
 *
 * <h2>What it does</h2>
 * <p>The same three steps a successful login performs, in the same order and for the same reasons:
 * register the user so the periodic refreshes see them, load the instrument master because tokens
 * cannot be decoded to symbols without it, then open the stream.</p>
 *
 * <p>A session that is not valid for today is not restored — {@code restorableUsers()} filters on
 * the trading date, so yesterday's token is correctly left alone and the user is asked to log in.</p>
 */
@Component
public class BrokerSessionRestorer {

    private static final Logger log = LoggerFactory.getLogger(BrokerSessionRestorer.class);

    private final KiteProperties properties;
    private final KiteSessionStore sessions;
    private final KiteInstrumentMaster instruments;
    private final KiteTickerManager tickers;
    private final UserRegistry users;

    public BrokerSessionRestorer(KiteProperties properties, KiteSessionStore sessions,
                                 KiteInstrumentMaster instruments, KiteTickerManager tickers,
                                 UserRegistry users) {
        this.properties = properties;
        this.sessions = sessions;
        this.instruments = instruments;
        this.tickers = tickers;
        this.users = users;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void restoreFeed() {
        if (!properties.isEnabled()) return;

        List<UserId> restorable;
        try {
            restorable = sessions.restorableUsers();
        } catch (RuntimeException e) {
            log.error("could not read stored broker sessions: {} — a login will be needed",
                    e.getMessage());
            return;
        }

        if (restorable.isEmpty()) {
            log.info("no broker session stored for today — the Kite login is needed before any "
                    + "market data arrives. The access token is issued per trading date, so "
                    + "yesterday's does not carry over.");
            return;
        }

        for (UserId userId : restorable) {
            try {
                users.ensure(userId);
                if (!instruments.isLoaded()) instruments.refresh(userId);
                tickers.connect(userId);
                log.info("restored the broker session for user={} from an earlier run — the feed is "
                        + "coming up without a new login", userId);

            } catch (RuntimeException e) {
                // One user's stale token must not stop another's session being restored, and it is
                // not fatal: the login page still works. Loud, though — an operator who assumes the
                // feed came back will not be watching for it.
                log.error("could not restore the broker session for user={}: {}. A fresh Kite login "
                        + "is needed before any market data will arrive.", userId, e.getMessage());
            }
        }
    }
}
