package com.equity.broker.kite;

import com.equity.broker.BrokerException;
import com.equity.domain.user.UserId;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongFunction;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps between trading symbols and the numeric instrument tokens Kite uses on the wire.
 *
 * <p>The streaming protocol carries only tokens, so without this the tick decoder cannot name what
 * it just decoded. Tokens are also not stable across corporate actions and re-listings, so the
 * master is reloaded each session rather than cached to disk and trusted.</p>
 *
 * <p>Lookups that fail return empty rather than a default. A subscription to the wrong token is
 * silent: the feed simply never sends that symbol, and the engine sees an instrument that never
 * trades instead of an error.</p>
 */
@Component
public class KiteInstrumentMaster {

    private static final Logger log = LoggerFactory.getLogger(KiteInstrumentMaster.class);

    private final KiteProperties properties;
    private final KiteHttp http;
    private final KiteCredentialsProvider credentials;
    private final KiteSessionStore sessions;

    private volatile Map<Long, KiteInstrument> byToken = Map.of();
    private volatile Map<String, KiteInstrument> bySymbol = Map.of();

    public KiteInstrumentMaster(KiteProperties properties, KiteHttp http,
                                KiteCredentialsProvider credentials, KiteSessionStore sessions) {
        this.properties = properties;
        this.http = http;
        this.credentials = credentials;
        this.sessions = sessions;
    }

    /**
     * Downloads and parses the full instrument dump using the session of the given user.
     *
     * <p>The dump is several megabytes of CSV and is not part of the authenticated JSON API, so it
     * is fetched directly rather than through {@link KiteHttp}, which unwraps a JSON envelope this
     * endpoint does not have.</p>
     */
    public int refresh(UserId userId) {
        KiteSession session = sessions.get(userId).orElseThrow(() ->
                new BrokerException("cannot load instruments: user " + userId + " has no Kite session",
                        "TokenException", false));
        KiteCredentials creds = credentials.require(userId);

        Request request = new Request.Builder()
                .url(properties.getInstrumentsUrl())
                .header("X-Kite-Version", "3")
                .header("Authorization", "token " + creds.apiKey() + ":" + session.accessToken())
                .get()
                .build();

        try (Response response = http.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new BrokerException("instrument download failed: HTTP " + response.code(),
                        "HttpError", response.code() >= 500);
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new BrokerException("instrument download returned no body", "HttpError", true);
            }
            try (Reader reader = new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8)) {
                return load(reader);
            }
        } catch (IOException e) {
            throw new BrokerException("instrument download failed", e);
        }
    }

    /** Parses a CSV dump. Exposed for tests and for a REPLAY run working from a saved dump. */
    public int load(String csv) {
        return load(new StringReader(csv));
    }

    public int load(Reader source) {
        Map<Long, KiteInstrument> tokens = new HashMap<>(120_000);
        Map<String, KiteInstrument> symbols = new HashMap<>(120_000);

        try (BufferedReader reader = new BufferedReader(source)) {
            String header = reader.readLine();
            if (header == null) {
                throw new BrokerException("instrument dump was empty", "DataException", true);
            }
            Map<String, Integer> col = headerIndex(header);

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = splitCsv(line);
                KiteInstrument inst = parse(fields, col);
                if (inst == null) continue;
                tokens.put(inst.instrumentToken(), inst);
                // A trading symbol is not unique across segments, so the NSE cash row wins: it is
                // the only thing this engine trades, and a derivative row silently taking the key
                // would point every subscription at the wrong instrument.
                KiteInstrument existing = symbols.get(inst.tradingSymbol());
                if (existing == null || (inst.isNseEquity() && !existing.isNseEquity())) {
                    symbols.put(inst.tradingSymbol(), inst);
                }
            }
        } catch (IOException e) {
            throw new BrokerException("instrument dump could not be read", e);
        }

        this.byToken = Collections.unmodifiableMap(tokens);
        this.bySymbol = Collections.unmodifiableMap(symbols);
        log.info("kite instrument master loaded: {} instruments, {} NSE equities",
                tokens.size(), tokens.values().stream().filter(KiteInstrument::isNseEquity).count());
        return tokens.size();
    }

    public Optional<KiteInstrument> bySymbol(String tradingSymbol) {
        return Optional.ofNullable(bySymbol.get(tradingSymbol));
    }

    public Optional<KiteInstrument> byToken(long token) {
        return Optional.ofNullable(byToken.get(token));
    }

    public Optional<Long> tokenFor(String tradingSymbol) {
        return bySymbol(tradingSymbol).map(KiteInstrument::instrumentToken);
    }

    /** The resolver the tick decoder needs. Returns null for a token that is not in the master. */
    public LongFunction<String> symbolResolver() {
        return token -> {
            KiteInstrument i = byToken.get(token);
            return i == null ? null : i.tradingSymbol();
        };
    }

    /**
     * NSE equities whose symbol or company name looks like the one asked for.
     *
     * <p>A configured constituent that resolves to nothing is almost never a typo — it is a symbol
     * that changed. Renames, demergers and index reshuffles happen several times a year, and the
     * only visible effect is that a stock silently stops being evaluated. The engine cannot pick the
     * replacement safely, but it can say what the candidates are, which turns a diagnosis into a
     * one-line edit.</p>
     *
     * <p>Matches a shared prefix of at least five characters in either direction, or the symbol
     * appearing in the instrument's company name. Deliberately loose: a list of five wrong guesses
     * costs a glance, and a missed rename costs a stock for the session.</p>
     */
    public List<String> suggestionsFor(String tradingSymbol) {
        if (tradingSymbol == null || tradingSymbol.isBlank() || byToken.isEmpty()) return List.of();
        String wanted = tradingSymbol.toUpperCase(java.util.Locale.ROOT);
        String stem = wanted.length() >= 5 ? wanted.substring(0, 5) : wanted;

        return bySymbol.values().stream()
                .filter(KiteInstrument::isNseEquity)
                .filter(i -> {
                    String symbol = i.tradingSymbol().toUpperCase(java.util.Locale.ROOT);
                    String name = i.name() == null
                            ? "" : i.name().toUpperCase(java.util.Locale.ROOT).replace(" ", "");
                    return symbol.startsWith(stem) || wanted.startsWith(
                            symbol.length() >= 5 ? symbol.substring(0, 5) : symbol)
                            || name.startsWith(stem);
                })
                .map(KiteInstrument::tradingSymbol)
                .sorted()
                .limit(6)
                .toList();
    }

    public boolean isLoaded() { return !byToken.isEmpty(); }

    public int size() { return byToken.size(); }

    private static KiteInstrument parse(List<String> fields, Map<String, Integer> col) {
        try {
            String symbol = get(fields, col, "tradingsymbol");
            if (symbol.isBlank()) return null;
            return new KiteInstrument(
                    Long.parseLong(get(fields, col, "instrument_token")),
                    symbol,
                    get(fields, col, "name"),
                    get(fields, col, "exchange"),
                    get(fields, col, "segment"),
                    get(fields, col, "instrument_type"),
                    parseDouble(get(fields, col, "tick_size")),
                    (int) parseDouble(get(fields, col, "lot_size")));
        } catch (RuntimeException e) {
            return null;   // one malformed row must not cost us the other hundred thousand
        }
    }

    private static double parseDouble(String s) {
        try {
            return s.isBlank() ? 0 : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String get(List<String> fields, Map<String, Integer> col, String name) {
        Integer idx = col.get(name);
        if (idx == null || idx >= fields.size()) return "";
        return fields.get(idx);
    }

    private static Map<String, Integer> headerIndex(String header) {
        Map<String, Integer> col = new HashMap<>();
        List<String> names = splitCsv(header);
        for (int i = 0; i < names.size(); i++) {
            col.put(names.get(i).trim().toLowerCase(), i);
        }
        return col;
    }

    /**
     * Minimal CSV split honouring double quotes.
     *
     * <p>Company names in the dump contain commas, so a plain split on comma shifts every column
     * after {@code name} for those rows — which silently corrupts tick size and lot size for exactly
     * the large-cap names this engine is most likely to trade.</p>
     */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>(16);
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
