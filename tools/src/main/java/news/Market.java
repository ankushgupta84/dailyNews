package news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
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

    public record Earning(String ticker, LocalDate date, String time) {}

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

    private final Fetcher fetcher;

    public Market(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Official close on or before {@code day} and its percentage change. Reads Nasdaq's quote endpoint,
     * which carries the session's close as soon as the market shuts; the historical endpoint can lag
     * by hours. Falls back to the historical endpoint when the quote has no "Closed at" block.
     * Tries the ticker as a stock, then as an ETF. Throws if neither source gives a usable price.
     */
    public Price price(String ticker, LocalDate day) throws IOException {
        IOException last = null;
        for (String assetClass : List.of("stocks", "etf")) {
            try {
                Price close = parseClose(ticker, day, fetcher.get(API + "/quote/" + ticker + "/info?assetclass=" + assetClass));
                if (close != null) {
                    return close;
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
                return parsePrice(ticker, fetcher.get(url));
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
                BigDecimal changePct = new BigDecimal(quote.path("percentageChange").asText()
                        .replace("%", "").replace("+", "").replace(",", "").strip()).setScale(1, RoundingMode.HALF_UP);
                return new Price(ticker, date, money(quote.path("lastSalePrice").asText()), changePct);
            } catch (RuntimeException e) { // NumberFormatException, DateTimeParseException
                throw new BadClose("unreadable quote for " + ticker + ": " + e.getMessage());
            }
        }
        return null;
    }

    static Price parsePrice(String ticker, String body) throws IOException {
        JsonNode rows = JSON.readTree(body).path("data").path("tradesTable").path("rows");
        if (!rows.isArray() || rows.size() < 2) {
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
