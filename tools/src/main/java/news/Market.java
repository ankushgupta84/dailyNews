package news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Prices and earnings dates from Nasdaq's public data API. */
public final class Market {

    public record Price(String ticker, LocalDate date, BigDecimal close, BigDecimal changePct) {}

    public record Earning(String ticker, LocalDate date, String time) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    private static final String API = "https://api.nasdaq.com/api";

    private final Fetcher fetcher;

    public Market(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Latest daily close on or before {@code day} and its change from the close before it.
     * Tries the ticker as a stock, then as an ETF. Throws if neither is known or fewer than
     * two closes come back.
     */
    public Price price(String ticker, LocalDate day) throws IOException {
        IOException last = null;
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
}
