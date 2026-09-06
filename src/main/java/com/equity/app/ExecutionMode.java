package com.equity.app;

/**
 * The only two modes. REPLAY runs historical data through the identical strategy engine and never
 * sends a broker order; LIVE uses real data and real orders. Nothing else exists — a paper mode
 * would be a third code path that silently drifts from the other two.
 */
public enum ExecutionMode { LIVE, REPLAY }
