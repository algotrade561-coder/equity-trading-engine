package com.equity.trading;

import com.equity.domain.position.Position;
import java.util.List;

/**
 * Where positions are kept between restarts.
 *
 * <p>Without this the engine comes back believing it holds nothing while the broker still holds
 * shares — and since the exit machinery only acts on positions it knows about, those shares would
 * sit with no stop, no target and no square-off. That is the single worst state this application can
 * be in, and it used to be one restart away.</p>
 *
 * <p>Design note 0.12 is unchanged: the broker is still truth and this is still a log. What the log
 * buys is the ability to ask the right question at startup — "what did I think I held?" — instead of
 * starting blind.</p>
 */
public interface PositionStore {

    void save(Position position);

    /** Positions that still held shares when the process stopped. */
    List<Position> loadOpen();

    /**
     * Every position opened today, whatever became of it.
     *
     * <p>Wider than {@link #loadOpen()} on purpose. Order tags have to stay unique for the whole
     * session, and a tag on a position that has since closed is every bit as reusable as one on a
     * position still running — the collision that misbooked a fill was against a stock the engine
     * had already finished with.</p>
     */
    List<Position> loadForToday();

    static PositionStore inMemory() {
        return new PositionStore() {
            @Override public void save(Position position) { }
            @Override public List<Position> loadOpen() { return List.of(); }
            @Override public List<Position> loadForToday() { return List.of(); }
        };
    }
}
