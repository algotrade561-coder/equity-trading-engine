package com.equity.api;

import com.equity.broker.BrokerPort;
import com.equity.broker.ConnectivityCheck;
import com.equity.broker.kite.KiteCredentialsProvider;
import com.equity.domain.user.UserId;
import com.equity.market.state.StructureEngine;
import com.equity.platform.security.CurrentUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The broker connectivity test: one after-market order, placed and cancelled.
 *
 * <p>Temporary by intent — a page to prove a user's key, session and source address work together
 * before Monday, especially after an Elastic IP is assigned. See {@link ConnectivityCheck} for why an
 * order is the only thing that proves it and how the order is kept harmless.</p>
 *
 * <p>Administrators only, and the test runs as the <b>signed-in</b> user, never as somebody else:
 * placing an order on another person's account, even a cancelled one, is not a diagnostic.</p>
 */
@RestController
@RequestMapping("/api/broker/kite/connectivity")
public class ConnectivityController {

    /** How far under the last price the default limit sits. Inside every NSE circuit band. */
    private static final double DEFAULT_DISCOUNT = 0.04;

    private final CurrentUser currentUser;
    private final ConnectivityCheck check;
    private final BrokerPort broker;
    private final KiteCredentialsProvider credentials;
    private final StructureEngine structure;

    public ConnectivityController(CurrentUser currentUser, BrokerPort broker,
                                  KiteCredentialsProvider credentials, StructureEngine structure) {
        this.currentUser = currentUser;
        this.check = new ConnectivityCheck(broker);
        this.broker = broker;
        this.credentials = credentials;
        this.structure = structure;
    }

    /** What the test would do, so the operator sees the price before anything is sent. */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam(defaultValue = "ITC") String symbol) {
        UserId me = currentUser.requireAdmin();
        String sym = symbol.trim().toUpperCase();
        double last = structure.state(sym).map(s -> s.lastPrice()).orElse(0.0);
        double suggested = last > 0 ? tick(last * (1 - DEFAULT_DISCOUNT)) : 0;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", sym);
        m.put("lastPrice", last > 0 ? last : null);
        m.put("suggestedLimit", suggested > 0 ? suggested : null);
        m.put("sourceIp", credentials.find(me).map(c -> c.sourceIp()).orElse(null));
        m.put("authenticated", broker.isAuthenticated(me));
        m.put("plan", "AMO LIMIT BUY 1 x " + sym + " CNC, then cancel it in the same request");
        return m;
    }

    public record RunRequest(String symbol, Double limitPrice) {}

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody RunRequest request) {
        UserId me = currentUser.requireAdmin();
        String sym = request.symbol() == null ? "" : request.symbol().trim().toUpperCase();
        if (sym.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a symbol is required");
        if (request.limitPrice() == null || !(request.limitPrice() > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "a positive limit price is required");
        }
        if (!broker.isAuthenticated(me)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no Kite session for your account today — log in to Kite first");
        }
        // Refuse a price at or above the market. The whole safety argument rests on the order being
        // unable to fill; an operator who mistypes should get a refusal, not a filled share.
        double last = structure.state(sym).map(s -> s.lastPrice()).orElse(0.0);
        if (last > 0 && request.limitPrice() >= last * 0.99) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.format(
                    "limit %.2f is not safely below the last price %.2f — use at most %.2f",
                    request.limitPrice(), last, tick(last * (1 - DEFAULT_DISCOUNT))));
        }

        String sourceIp = credentials.find(me).map(c -> c.sourceIp()).orElse(null);
        ConnectivityCheck.Report report = check.run(me, sym, request.limitPrice(), sourceIp);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", report.connected());
        m.put("orderStillLive", report.orderStillLive());
        m.put("brokerOrderId", report.brokerOrderId());
        m.put("sourceIp", report.sourceIp() == null ? "default interface" : report.sourceIp());
        m.put("steps", report.steps().stream().map(s -> Map.of(
                "name", s.name(), "ok", s.ok(), "detail", s.detail())).toList());
        return m;
    }

    /** Rounds down to the 5-paise tick, which is what the exchange accepts. */
    private static double tick(double price) {
        return Math.floor(price * 20) / 20.0;
    }
}
