package com.equity.store;

import com.equity.broker.ProductType;
import com.equity.domain.Direction;
import com.equity.domain.momentum.EntryPattern;
import com.equity.domain.order.OrderTag;
import com.equity.domain.position.ExitReason;
import com.equity.domain.position.Position;
import com.equity.domain.position.PositionStatus;
import com.equity.domain.user.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A position, on disk.
 *
 * <p>This is the record that makes a restart survivable. Without it the engine comes back believing
 * it holds nothing, while the broker still holds shares — and the exit machinery, which only acts on
 * positions it knows about, would leave them there. Design note 0.12 still applies: the broker is
 * truth and this is a log, but a log that lets the engine ask the right question at startup.</p>
 *
 * <p>Indexed on the trading date because every question asked of it is bounded by a session.</p>
 */
@Entity
@Table(name = "position", indexes = {
        @Index(name = "idx_position_user_date", columnList = "trading_user_id,trading_date"),
        @Index(name = "idx_position_status", columnList = "status")
})
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "position_id", nullable = false, unique = true, length = 36)
    private String positionId;

    @Column(name = "trading_user_id", nullable = false, length = 36)
    private String tradingUserId;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(nullable = false, length = 32)
    private String symbol;

    @Column(nullable = false, length = 8)
    private String direction;

    @Column(length = 40)
    private String pattern;

    @Column(nullable = false, length = 8)
    private String product;

    @Column(nullable = false, length = 24)
    private String status;

    private int quantity;
    private int filledQuantity;
    private double intendedEntryPrice;
    private double entryPrice;
    private double stopPrice;
    private double targetPrice;
    private double highWaterMark;

    private Instant openedAt;
    private Instant closedAt;
    private double exitPrice;

    @Column(length = 24)
    private String exitReason;

    @Column(length = 24)
    private String entryTag;
    @Column(length = 64)
    private String entryOrderId;
    @Column(length = 24)
    private String exitTag;
    @Column(length = 64)
    private String exitOrderId;

    protected PositionEntity() {}

    public static PositionEntity from(Position p, LocalDate tradingDate) {
        PositionEntity e = new PositionEntity();
        e.positionId = p.id().toString();
        e.tradingDate = tradingDate;
        e.apply(p);
        return e;
    }

    /** Copies the mutable half. The identity columns are set once and never rewritten. */
    public void apply(Position p) {
        this.tradingUserId = p.userId().toString();
        this.symbol = p.symbol();
        this.direction = p.direction().name();
        this.pattern = p.pattern() == null ? null : p.pattern().name();
        this.product = p.product().name();
        this.status = p.status().name();
        this.quantity = p.quantity();
        this.filledQuantity = p.filledQuantity();
        this.intendedEntryPrice = p.intendedEntryPrice();
        this.entryPrice = p.entryPrice();
        this.stopPrice = p.stopPrice();
        this.targetPrice = p.targetPrice();
        this.highWaterMark = p.highWaterMark();
        this.openedAt = p.openedAt();
        this.closedAt = p.closedAt();
        this.exitPrice = p.exitPrice();
        this.exitReason = p.exitReason() == null ? null : p.exitReason().name();
        this.entryTag = p.entryTag() == null ? null : p.entryTag().value();
        this.entryOrderId = p.entryOrderId();
        this.exitTag = p.exitTag() == null ? null : p.exitTag().value();
        this.exitOrderId = p.exitOrderId();
    }

    public Position toPosition() {
        return new Position(
                UUID.fromString(positionId),
                UserId.of(tradingUserId),
                symbol,
                Direction.valueOf(direction),
                pattern == null ? null : EntryPattern.valueOf(pattern),
                ProductType.valueOf(product),
                PositionStatus.valueOf(status),
                quantity, filledQuantity, intendedEntryPrice, entryPrice,
                stopPrice, targetPrice, highWaterMark,
                openedAt, closedAt, exitPrice,
                exitReason == null ? null : ExitReason.valueOf(exitReason),
                entryTag == null ? null : new OrderTag(entryTag), entryOrderId,
                exitTag == null ? null : new OrderTag(exitTag), exitOrderId);
    }

    public String getPositionId()    { return positionId; }
    public String getTradingUserId() { return tradingUserId; }
    public LocalDate getTradingDate() { return tradingDate; }
    public String getStatus()        { return status; }
    public String getSymbol()        { return symbol; }
}
