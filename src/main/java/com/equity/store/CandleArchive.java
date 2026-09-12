package com.equity.store;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Where a session's bars go once they leave the database.
 *
 * <p>One method, and it must be atomic from the caller's point of view: either the whole file is
 * at the destination and readable, or the call throws. The archiver deletes the database rows only
 * after this returns, so a destination that returns on a half-written object is a destination
 * that loses a session. Both implementations therefore write to a temporary name or verify the
 * upload before they return.</p>
 */
public interface CandleArchive {

    /**
     * Stores a finished, gzipped CSV for one session.
     *
     * @param tradingDate the session the file holds
     * @param file        the complete file on local disk; the archive may read it but must not delete it
     * @param rows        the number of data rows in it, for the destination's own record
     * @return where it went, for the log — a path or an S3 URI
     */
    String store(LocalDate tradingDate, Path file, long rows) throws IOException;

    /** A short description of the destination, for the start-up log. */
    String describe();
}
