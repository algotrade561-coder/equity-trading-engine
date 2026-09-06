package com.equity.market.universe;

import com.equity.platform.time.TradingClock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The index constituents, fetched from the exchange rather than typed into the source.
 *
 * <h2>Why not a list in the code</h2>
 * <p>A hardcoded constituent list rots silently and expensively. Two entries in the previous list —
 * {@code TATAMOTORS} and {@code LTIM} — had been renamed by the exchange and resolved to nothing, so
 * two stocks were never evaluated while the engine reported a universe of fifty. Nothing failed.
 * That failure mode scales with the list: at five hundred names, hand-maintenance is not a plan.</p>
 *
 * <p>NSE publishes the constituents of every index as CSV and the file needs no authentication, so
 * the engine reads the index from the body that defines it. Renames, additions and removals arrive
 * on their own.</p>
 *
 * <h2>Failing on a fetch</h2>
 * <p>Every successful fetch is written to disk, and a failed one falls back to that copy however old
 * it is. The alternative — an empty universe at 09:15 because a CSV host was briefly unreachable —
 * is a worse outcome than a constituent list a few days stale, since index membership changes a
 * handful of times a year. With no cache either, it returns nothing and says so loudly: an engine
 * watching nothing is safe, and a silent one is not.</p>
 */
@Component
public class IndexConstituentSource {

    private static final Logger log = LoggerFactory.getLogger(IndexConstituentSource.class);

    /**
     * NSE serves the archive CSVs only to something that looks like a browser; the default Java
     * agent gets a 403. This is not evasion — the file is public and unauthenticated — it is the
     * header the host requires to serve it at all.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0 Safari/537.36";

    private final UniverseProperties properties;
    private final TradingClock clock;
    private final HttpClient http;

    private final AtomicReference<List<String>> cached = new AtomicReference<>(List.of());
    private volatile LocalDate fetchedOn;

    public IndexConstituentSource(UniverseProperties properties, TradingClock clock) {
        this.properties = properties;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * The constituents, fetched at most once per trading date.
     *
     * <p>Never throws and never returns null. Callers subscribe to whatever comes back; an empty
     * result means the engine watches nothing, which is the correct reading of "the index could not
     * be established".</p>
     */
    public List<String> constituents() {
        LocalDate today = clock.tradingDate();
        List<String> current = cached.get();
        if (today.equals(fetchedOn) && !current.isEmpty()) return current;

        List<String> fetched = fetch();
        if (!fetched.isEmpty()) {
            cached.set(fetched);
            fetchedOn = today;
            writeCache(fetched);
            log.info("{} constituents loaded from the exchange: {} symbols",
                    properties.getIndexName(), fetched.size());
            return fetched;
        }

        List<String> fromDisk = readCache();
        if (!fromDisk.isEmpty()) {
            cached.set(fromDisk);
            fetchedOn = today;      // do not hammer a host that is evidently unwell
            log.warn("could not fetch {} from the exchange — falling back to the {} symbol(s) "
                            + "cached at {}. Index membership changes rarely, so this is safe for "
                            + "a session, but it will go stale.",
                    properties.getIndexName(), fromDisk.size(), properties.getCacheFile());
            return fromDisk;
        }

        log.error("could not establish the {} constituents and there is no cached copy at {}. "
                        + "THE ENGINE IS WATCHING NOTHING. Check network access to {}, or set "
                        + "equity.universe.symbols explicitly to override.",
                properties.getIndexName(), properties.getCacheFile(), properties.getIndexUrl());
        return List.of();
    }

    /** Forces the next call to go back to the exchange. */
    public void invalidate() {
        fetchedOn = null;
    }

    // ── Fetching ─────────────────────────────────────────────────────────────

    private List<String> fetch() {
        String url = properties.getIndexUrl();
        if (url == null || url.isBlank()) return List.of();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/csv,text/plain,*/*")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("{} returned HTTP {}", url, response.statusCode());
                return List.of();
            }
            return parse(response.body());

        } catch (IOException e) {
            log.warn("could not reach {}: {}", url, e.toString());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (RuntimeException e) {
            log.warn("could not read the constituent list from {}: {}", url, e.toString());
            return List.of();
        }
    }

    /**
     * Reads the {@code Symbol} column of the exchange CSV.
     *
     * <p>Columns are located by header name rather than by position: the file has gained and lost
     * columns over the years, and an index into a row is a bug waiting for the next revision.</p>
     *
     * <p>Only the {@code EQ} series is taken. The others are not ordinary intraday-tradeable equity —
     * {@code BE} is trade-to-trade with no intraday square-off at all, which is precisely the wrong
     * thing to hand an intraday engine.</p>
     */
    static List<String> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        String[] lines = csv.split("\\R");
        if (lines.length < 2) return List.of();

        List<String> header = splitCsv(lines[0]);
        int symbolAt = indexOfHeader(header, "Symbol");
        int seriesAt = indexOfHeader(header, "Series");
        if (symbolAt < 0) return List.of();

        Set<String> symbols = new LinkedHashSet<>();
        for (int i = 1; i < lines.length; i++) {
            List<String> row = splitCsv(lines[i]);
            if (row.size() <= symbolAt) continue;

            String symbol = row.get(symbolAt).trim().toUpperCase(java.util.Locale.ROOT);
            if (symbol.isEmpty()) continue;
            // NSE seeds these files with placeholder rows for suspended or reconstituted names.
            if (symbol.startsWith("DUMMY")) continue;
            if (seriesAt >= 0 && row.size() > seriesAt
                    && !row.get(seriesAt).trim().equalsIgnoreCase("EQ")) {
                continue;
            }
            symbols.add(symbol);
        }
        return List.copyOf(symbols);
    }

    private static int indexOfHeader(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            // The file is served with a UTF-8 BOM, which otherwise glues itself to the first header.
            String cell = header.get(i).replace("﻿", "").trim();
            if (cell.equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** Minimal CSV split honouring quoted fields — company names contain commas. */
    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString());
        return out;
    }

    // ── The on-disk fallback ─────────────────────────────────────────────────

    private void writeCache(List<String> symbols) {
        Path path = Path.of(properties.getCacheFile());
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.write(path, symbols);
        } catch (IOException e) {
            // Not fatal: the cache only matters on a day the fetch fails.
            log.warn("could not write the constituent cache to {}: {}", path, e.toString());
        }
    }

    private List<String> readCache() {
        Path path = Path.of(properties.getCacheFile());
        if (!Files.isReadable(path)) return List.of();
        try {
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException e) {
            log.warn("could not read the constituent cache at {}: {}", path, e.toString());
            return List.of();
        }
    }
}
