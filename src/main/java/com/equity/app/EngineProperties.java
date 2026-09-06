package com.equity.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level engine switches.
 *
 * <p>Everything capable of placing an order ships OFF. A feature that defaults to ON is a feature
 * nobody decided to run.</p>
 */
@ConfigurationProperties(prefix = "engine")
public class EngineProperties {

    /** LIVE or REPLAY. There is no paper mode. */
    private ExecutionMode mode = ExecutionMode.REPLAY;

    /** Master kill switch. No entry order may be submitted while this is false. */
    private boolean tradingEnabled = false;


    public ExecutionMode getMode() { return mode; }
    public void setMode(ExecutionMode mode) { this.mode = mode; }

    public boolean isTradingEnabled() { return tradingEnabled; }
    public void setTradingEnabled(boolean v) { this.tradingEnabled = v; }
}
