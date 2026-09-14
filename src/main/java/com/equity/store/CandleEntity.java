package com.equity.store;

import com.equity.domain.market.Candle;
import com.equity.domain.market.Timeframe;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A completed 1-minute bar, on disk.
 *
 * <p>The engine already builds these from the tick stream and used to discard them at shutdown, so
 * a restart began the session over: twenty bars of warm-up before anything could be evaluated, and —
 * worse, because it never announced itself — a VWAP computed from the process's start rather than
 * from the open, for the rest of the day.</p>
 *
 * <p>Storing them costs one row per symbol per minute and removes the whole problem without any
 * dependency on the broker. Kite does sell an intraday history add-on and the engine will use it
 * when present, but the data was already flowing through this process; buying back something we
 * had and threw away is the wrong trade.</p>
 *
 * <p>Unique on symbol and bar start, so replaying or double-writing a minute cannot duplicate it.</p>
 */
@Entity
@Table(name = "candle_m1",
        uniqueConstraints = @UniqueConstraint(name = "uk_candle_symbol_start",
                columnNames = {"symbol", "start_time"}),
        indexes = @Index(name = "idx_candle_date", columnList = "trading_date"))
public class CandleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String symbol;

    /** Indexed and used for both loading a session and pruning old ones. */
    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "open_price", nullable = false)  private double open;
    @Column(name = "high_price", nullable = false)  private double high;
    @Column(name = "low_price", nullable = false)   private double low;
    @Column(name = "close_price", nullable = false) private double close;
    @Column(nullable = false)                        private long volume;

    /**
     * The exchange's day figures as the engine held them when this bar closed — previous close,
     * day open, and the running day high and low. The strategy's day-change and
     * distance-from-high gates read these off the tick, and the tick's day high routinely sits
     * above anything the bars print (a spike the one-second quote stream never carried). Without
     * them a replay of these bars cannot reproduce the gates, and it was letting through setups
     * the live engine refused. Nullable: bars stored before this existed have none.
     */
    @Column(name = "previous_close") private Double previousClose;
    @Column(name = "day_open")       private Double dayOpen;
    @Column(name = "day_high")       private Double dayHigh;
    @Column(name = "day_low")        private Double dayLow;

    protected CandleEntity() { }

    public CandleEntity(Candle c, LocalDate tradingDate) {
        this.symbol = c.symbol();
        this.tradingDate = tradingDate;
        this.startTime = c.startTime();
        this.open = c.open();
        this.high = c.high();
        this.low = c.low();
        this.close = c.close();
        this.volume = c.volume();
    }

    /** A bar together with the day context at its close. */
    public CandleEntity(Candle c, LocalDate tradingDate, DaySnapshot day) {
        this(c, tradingDate);
        if (day != null) {
            this.previousClose = positiveOrNull(day.previousClose());
            this.dayOpen = positiveOrNull(day.dayOpen());
            this.dayHigh = positiveOrNull(day.dayHigh());
            this.dayLow = positiveOrNull(day.dayLow());
        }
    }

    /** What the store captures on the feed thread when a bar closes. Zero means "not known". */
    public record DaySnapshot(double previousClose, double dayOpen, double dayHigh, double dayLow) {}

    private static Double positiveOrNull(double v) {
        return v > 0 && !Double.isNaN(v) ? v : null;
    }

    public Double previousClose() { return previousClose; }
    public Double dayOpen()       { return dayOpen; }
    public Double dayHigh()       { return dayHigh; }
    public Double dayLow()        { return dayLow; }

    public Candle toCandle() {
        return new Candle(symbol, Timeframe.M1, startTime, open, high, low, close, volume);
    }

    public String symbol()      { return symbol; }
    public Instant startTime()  { return startTime; }
}
