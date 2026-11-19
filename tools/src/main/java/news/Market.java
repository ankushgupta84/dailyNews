package news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prices and earnings dates from Nasdaq's public data API. */
public final class Market {

    public record Price(String ticker, LocalDate date, BigDecimal close, BigDecimal changePct) {}

    /** A live quote taken while the market is open. {@code changePct} is measured from the previous close. */
    public record OpenQuote(BigDecimal price, BigDecimal changePct, String timestamp) {}

    /**
     * Everything one info lookup yields. {@code open} is null when the market is not open or the live
     * quote is unreadable; in the second case {@code openProblem} says why, so the brief can record it.
     */
    public record Reading(Price close, OpenQuote open, String openProblem) {}

    public record Earning(String ticker, LocalDate date, String time) {}

    /** The latest reported quarter: earnings per share against the consensus forecast, as Nasdaq lists them. */
    public record EarningsResult(String ticker, String fiscalQuarter, LocalDate dateReported, BigDecimal eps,
                                 BigDecimal consensus, BigDecimal surprisePct) {}

    /**
     * A news headline about a ticker. {@code published} is a day: the API gives no time. {@code primary} is
     * true when Nasdaq files the article under this ticker, false when the ticker is only among its related
     * symbols (the article is then usually about another company).
     */
    public record Headline(String title, String url, LocalDate published, String publisher, boolean primary) {}

    static final int MAX_HEADLINES = 4;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final DateTimeFormatter NEWS_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final String API = "https://api.nasdaq.com/api";
    private static final String NASDAQ = "https://www.nasdaq.com";

    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** The brief shows this label to the reader, which must fit the edition schema's openAsOf limit. */
    static final int MAX_TIMESTAMP = 60;

    private final Fetcher fetcher;
    private final Clock clock;

    public Market(Fetcher fetcher, Clock clock) {
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public Market(Fetcher fetcher) {
        this(fetcher, Clock.systemUTC());
    }

    /** The official close alone; see {@link #read}. */
    public Price price(String ticker, LocalDate day) throws IOException {
        return read(ticker, day).close();
    }

    /**
     * Official close on or before {@code day}, its percentage change, and the live quote if the market
     * is open. One quote response serves both. The quote endpoint carries the session's close as soon as
     * the market shuts; the historical endpoint can lag by hours. Falls back to the historical endpoint
     * when the quote has no "Closed at" block. While the market is open, a history row or close dated
     * today (New York) is a partial session, never the previous close, and is ignored.
     * Tries the ticker as a stock, then as an ETF. Throws if neither source gives a usable price.
     */
    public Reading read(String ticker, LocalDate day) throws IOException {
        IOException last = null;
        LocalDate today = LocalDate.now(clock.withZone(NEW_YORK));
        boolean marketOpen = false;
        boolean quoted = false;
        OpenQuote open = null;
        String openProblem = null;
        for (String assetClass : List.of("stocks", "etf")) {
            try {
                String body = fetcher.get(API + "/quote/" + ticker + "/info?assetclass=" + assetClass);
                Price close = parseClose(ticker, day, body);
                if (!quoted) { // the first asset class that knows the symbol decides
                    quoted = true;
                    marketOpen = isOpen(body);
                    try {
                        open = parseOpen(ticker, body);
                    } catch (IOException e) {
                        openProblem = e.getMessage();
                    }
                }
                if (close != null && !(marketOpen && close.date().equals(today))) {
                    return new Reading(close, open, openProblem);
                }
            } catch (IOException e) {
                if (e instanceof BadClose) {
                    throw e; // a close was reported but cannot be trusted: do not paper over it with older data
                }
                last = e; // quote endpoint down or symbol unknown here: try the next class, then history
            }
        }
        for (String assetClass : List.of("stocks", "etf")) {
            String url = API + "/quote/" + ticker + "/historical?assetclass=" + assetClass
                    + "&fromdate=" + day.minusDays(12) + "&todate=" + day + "&limit=5";
            try {
                return new Reading(parsePrice(ticker, fetcher.get(url), marketOpen ? today : null), open, openProblem);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    /** The quote reported a close that is unreadable or dated after the requested day. */
    private static final class BadClose extends IOException {
        BadClose(String message) {
            super(message);
        }
    }

    private static final Pattern CLOSED_AT = Pattern.compile("^Closed at (\\w{3} \\d{1,2}, \\d{4}) .*");

    /**
     * The official close from a quote response, or null if it has no "Closed at" block. Which of
     * primaryData and secondaryData holds the block depends on the time of day (after hours, the
     * close moves to secondaryData). Throws IOException if the symbol is unknown to this asset class.
     */
    static Price parseClose(String ticker, LocalDate day, String body) throws IOException {
        JsonNode data = JSON.readTree(body).path("data");
        if (!data.isObject()) { // an unknown symbol answers HTTP 200 with "data": null
            throw new IOException("no quote for " + ticker);
        }
        for (String block : List.of("primaryData", "secondaryData")) {
            JsonNode quote = data.path(block); // secondaryData may be null
            Matcher closed = CLOSED_AT.matcher(quote.path("lastTradeTimestamp").asText());
            if (!closed.matches()) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(closed.group(1), NEWS_DATE);
                if (date.isAfter(day)) {
                    throw new BadClose("close for " + ticker + " is dated " + date + ", after " + day);
                }
                return new Price(ticker, date, money(quote.path("lastSalePrice").asText()),
                        percent(quote.path("percentageChange").asText()));
            } catch (RuntimeException e) { // NumberFormatException, DateTimeParseException
                throw new BadClose("unreadable quote for " + ticker + ": " + e.getMessage());
            }
        }
        return null;
    }

    /** True when the quote says the regular session is under way. Anything else, or no status, is not open. */
    static boolean isOpen(String body) throws IOException {
        return JSON.readTree(body).path("data").path("marketStatus").asText().equalsIgnoreCase("Open");
    }

    /**
     * The live quote, or null when the market is not open. While open, primaryData is the live quote and
     * its change is measured from the previous close. Throws IOException if the market says it is open
     * but the price, change or timestamp cannot be read. This shape is not documented by Nasdaq.
     */
    static OpenQuote parseOpen(String ticker, String body) throws IOException {
        if (!isOpen(body)) {
            return null;
        }
        JsonNode quote = JSON.readTree(body).path("data").path("primaryData");
        String timestamp = quote.path("lastTradeTimestamp").asText().strip();
        try {
            if (timestamp.isEmpty() || timestamp.length() > MAX_TIMESTAMP) {
                throw new IllegalArgumentException("timestamp \"" + timestamp + "\" is empty or too long");
            }
            return new OpenQuote(money(quote.path("lastSalePrice").asText()), percent(quote.path("percentageChange").asText()), timestamp);
        } catch (RuntimeException e) { // NumberFormatException from a bad price or change
            throw new IOException("unreadable opening quote for " + ticker + ": " + e.getMessage());
        }
    }

    private static BigDecimal percent(String text) {
        return new BigDecimal(text.replace("%", "").replace("+", "").replace(",", "").strip()).setScale(1, RoundingMode.HALF_UP);
    }

    static Price parsePrice(String ticker, String body) throws IOException {
        return parsePrice(ticker, body, null);
    }

    /** Computes the change from the two latest rows, skipping any row dated {@code partialDay} (an unfinished session). */
    static Price parsePrice(String ticker, String body, LocalDate partialDay) throws IOException {
        JsonNode all = JSON.readTree(body).path("data").path("tradesTable").path("rows");
        List<JsonNode> rows = new ArrayList<>();
        if (all.isArray()) {
            String skip = partialDay == null ? null : partialDay.format(US_DATE);
            all.forEach(r -> {
                if (!r.path("date").asText().equals(skip)) {
                    rows.add(r);
                }
            });
        }
        if (rows.size() < 2) {
            throw new IOException("no price history for " + ticker);
        }
        try {
            BigDecimal latest = money(rows.get(0).path("close").asText());
            BigDecimal before = money(rows.get(1).path("close").asText());
            if (before.signum() <= 0) {
                throw new IOException("bad previous close for " + ticker);
            }
            BigDecimal changePct = latest.subtract(before).multiply(BigDecimal.valueOf(100))
                    .divide(before, 1, RoundingMode.HALF_UP);
            LocalDate date = LocalDate.parse(rows.get(0).path("date").asText(), US_DATE);
            return new Price(ticker, date, latest, changePct);
        } catch (RuntimeException e) { // NumberFormatException, DateTimeParseException
            throw new IOException("unreadable price row for " + ticker + ": " + e.getMessage(), e);
        }
    }

    private static BigDecimal money(String text) {
        return new BigDecimal(text.replace("$", "").replace(",", "").strip());
    }

    /** Every company reporting on {@code day}. Weekends have none and are not requested. */
    public List<Earning> earningsOn(LocalDate day) throws IOException {
        if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return List.of();
        }
        return parseEarnings(day, fetcher.get(API + "/calendar/earnings?date=" + day));
    }

    static List<Earning> parseEarnings(LocalDate day, String body) throws IOException {
        JsonNode data = JSON.readTree(body).path("data");
        if (!data.isObject()) {
            throw new IOException("no earnings calendar for " + day);
        }
        List<Earning> out = new ArrayList<>();
        for (JsonNode row : data.path("rows")) { // "rows" is null on days with no reports
            String time = switch (row.path("time").asText()) {
                case "time-pre-market" -> "before the open";
                case "time-after-hours" -> "after the close";
                default -> "";
            };
            out.add(new Earning(row.path("symbol").asText().toUpperCase(Locale.ROOT), day, time));
        }
        return out;
    }

    /** The most recently reported quarter for {@code ticker}. Throws if the table is missing or unreadable. */
    public EarningsResult latestEarnings(String ticker) throws IOException {
        return parseEarningsResult(ticker, fetcher.get(API + "/company/" + ticker + "/earnings-surprise"));
    }

    static EarningsResult parseEarningsResult(String ticker, String body) throws IOException {
        JsonNode rows = JSON.readTree(body).path("data").path("earningsSurpriseTable").path("rows");
        EarningsResult latest = null;
        for (JsonNode row : rows) {
            try {
                EarningsResult r = new EarningsResult(ticker, row.path("fiscalQtrEnd").asText(),
                        LocalDate.parse(row.path("dateReported").asText(), DateTimeFormatter.ofPattern("M/d/yyyy")),
                        new BigDecimal(row.path("eps").asText()), new BigDecimal(row.path("consensusForecast").asText()),
                        new BigDecimal(row.path("percentageSurprise").asText()));
                if (latest == null || r.dateReported().isAfter(latest.dateReported())) {
                    latest = r;
                }
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new IOException("unreadable earnings row for " + ticker + ": " + row);
            }
        }
        if (latest == null) {
            throw new IOException("no earnings results for " + ticker);
        }
        return latest;
    }

    /**
     * Up to {@link #MAX_HEADLINES} headlines about {@code ticker} published on or after {@code since},
     * newest first. Tries the ticker as a stock, then as an ETF. An empty list means no recent news;
     * if a lookup failed and nothing was found, the failure is thrown rather than read as "no news".
     */
    public List<Headline> headlines(String ticker, LocalDate since) throws IOException {
        IOException last = null;
        for (String assetClass : List.of("STOCKS", "ETF")) {
            String url = API + "/news/topic/articlebysymbol?q=" + ticker + "%7C" + assetClass
                    + "&offset=0&limit=10&fallback=false";
            try {
                List<Headline> found = parseHeadlines(ticker, since, fetcher.get(url));
                if (!found.isEmpty()) {
                    return found;
                }
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return List.of();
    }

    static List<Headline> parseHeadlines(String ticker, LocalDate since, String body) throws IOException {
        JsonNode rows = JSON.readTree(body).path("data").path("rows");
        if (!rows.isArray()) {
            throw new IOException("no news list for " + ticker);
        }
        String symbol = ticker.toLowerCase(Locale.ROOT);
        List<Headline> out = new ArrayList<>();
        for (JsonNode row : rows) {
            // For a symbol Nasdaq does not know under that asset class, the API answers with the
            // day's general news instead of an error. Keep only articles that name this ticker.
            boolean primary = row.path("primarysymbol").asText().equalsIgnoreCase(symbol);
            if (!primary && !related(row, symbol)) {
                continue;
            }
            LocalDate published;
            try {
                published = LocalDate.parse(row.path("created").asText(), NEWS_DATE);
            } catch (DateTimeParseException e) {
                continue; // undated, like an undated feed item: cannot tell whether it is recent
            }
            String path = row.path("url").asText().strip();
            String url = path.startsWith("/") ? NASDAQ + path : path;
            String title = row.path("title").asText().strip();
            if (published.isBefore(since) || title.isEmpty() || !url.startsWith("https://")) {
                continue;
            }
            out.add(new Headline(title, url, published, row.path("publisher").asText().strip(), primary));
        }
        return out.stream().sorted(Comparator.comparing(Headline::published).reversed()).limit(MAX_HEADLINES).toList();
    }

    private static boolean related(JsonNode row, String symbol) {
        for (JsonNode related : row.path("related_symbols")) {
            if (related.asText().toLowerCase(Locale.ROOT).startsWith(symbol + "|")) {
                return true;
            }
        }
        return false;
    }
}
