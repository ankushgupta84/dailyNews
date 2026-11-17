package news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketTest {

    static String history(String... dateAndClose) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < dateAndClose.length; i += 2) {
            rows.append(i == 0 ? "" : ",").append("{\"date\":\"").append(dateAndClose[i])
                    .append("\",\"close\":\"").append(dateAndClose[i + 1]).append("\"}");
        }
        return "{\"data\":{\"tradesTable\":{\"rows\":[" + rows + "]}}}";
    }

    static final String UNKNOWN = "{\"data\":null,\"status\":{\"rCode\":400}}";

    @Test
    void computesChangeFromTheTwoLatestCloses() throws IOException {
        Market.Price p = Market.parsePrice("NVDA", history("11/13/2026", "$162.85", "11/12/2026", "$166.19"));
        assertEquals(LocalDate.of(2026, 11, 13), p.date());
        assertEquals(new BigDecimal("162.85"), p.close());
        assertEquals(new BigDecimal("-2.0"), p.changePct());
    }

    @Test
    void readsEtfPricesWithoutDollarSignAndStockPricesWithCommas() throws IOException {
        assertEquals(new BigDecimal("-2.9"), Market.parsePrice("DRAM", history("11/13/2026", "42.40", "11/12/2026", "43.66")).changePct());
        assertEquals(new BigDecimal("1,413.53".replace(",", "")), Market.parsePrice("ASML", history("11/13/2026", "$1,413.53", "11/12/2026", "$1,400.00")).close());
    }

    @Test
    void roundsHalfUpToOneDecimal() throws IOException {
        // 100 -> 103.05 is +3.05%, which rounds to 3.1; 100 -> 96.95 is -3.05%, which rounds to -3.1.
        assertEquals(new BigDecimal("3.1"), Market.parsePrice("A", history("11/13/2026", "103.05", "11/12/2026", "100")).changePct());
        assertEquals(new BigDecimal("-3.1"), Market.parsePrice("A", history("11/13/2026", "96.95", "11/12/2026", "100")).changePct());
        assertEquals(new BigDecimal("0.0"), Market.parsePrice("A", history("11/13/2026", "100", "11/12/2026", "100")).changePct());
    }

    @Test
    void refusesUnusablePriceData() {
        assertThrows(IOException.class, () -> Market.parsePrice("X", UNKNOWN));
        assertThrows(IOException.class, () -> Market.parsePrice("X", history("11/13/2026", "$5.00")));
        assertThrows(IOException.class, () -> Market.parsePrice("X", history("11/13/2026", "N/A", "11/12/2026", "$5.00")));
        assertThrows(IOException.class, () -> Market.parsePrice("X", history("11/13/2026", "$5.00", "11/12/2026", "$0.00")));
        assertThrows(IOException.class, () -> Market.parsePrice("X", history("2026-11-13", "$5.00", "11/12/2026", "$4.00")));
        assertThrows(IOException.class, () -> Market.parsePrice("X", "<html>blocked</html>"));
    }

    @Test
    void fallsBackFromStockToEtfAndReportsUnknownTickers() throws IOException {
        List<String> asked = new ArrayList<>();
        Fetcher fetcher = new FakeFetcher(url -> {
            asked.add(url);
            if (url.contains("/VOO/") && url.contains("assetclass=etf")) {
                return history("11/13/2026", "693.38", "11/12/2026", "696.00");
            }
            return UNKNOWN;
        });
        Market market = new Market(fetcher);
        assertEquals(new BigDecimal("-0.4"), market.price("VOO", LocalDate.of(2026, 11, 16)).changePct());
        assertEquals(4, asked.size()); // quote as stock and ETF found no close, then history as stock and ETF
        assertThrows(IOException.class, () -> market.price("LKNCY", LocalDate.of(2026, 11, 16)));
    }

    /** A quote response as Nasdaq sends it: each block is "price|change|timestamp", or null for a JSON null. */
    static String info(String primary, String secondary) {
        return "{\"data\":{\"symbol\":\"X\",\"primaryData\":" + block(primary) + ",\"secondaryData\":" + block(secondary)
                + "},\"message\":null,\"status\":{\"rCode\":200}}";
    }

    private static String block(String spec) {
        if (spec == null) {
            return "null";
        }
        String[] f = spec.split("\\|");
        return "{\"lastSalePrice\":\"" + f[0] + "\",\"percentageChange\":\"" + f[1] + "\",\"lastTradeTimestamp\":\"" + f[2] + "\"}";
    }

    static final String UNKNOWN_QUOTE = "{\"data\":null,\"message\":null,\"status\":{\"rCode\":400,"
            + "\"bCodeMessage\":[{\"code\":1001,\"errorMessage\":\"Symbol not exists.\"}]}}";
    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);
    private static final String AFTER_HOURS = "$155.598|-0.35%|Nov 16, 2026 7:59 PM ET";
    private static final String CLOSE = "$156.14|-4.12%|Closed at Nov 16, 2026 4:00 PM ET";

    @Test
    void readsTheOfficialCloseFromSecondaryDataAfterHours() throws IOException {
        Market.Price p = Market.parseClose("NVDA", MONDAY, info(AFTER_HOURS, CLOSE));
        assertEquals(MONDAY, p.date());
        assertEquals(new BigDecimal("156.14"), p.close());
        assertEquals(new BigDecimal("-4.1"), p.changePct());
    }

    @Test
    void readsTheCloseFromPrimaryDataWhenSecondaryDataIsNull() throws IOException {
        Market.Price p = Market.parseClose("NVDA", MONDAY, info("$1,156.14|+4.15%|Closed at Nov 16, 2026 4:00 PM ET", null));
        assertEquals(new BigDecimal("1156.14"), p.close());
        assertEquals(new BigDecimal("4.2"), p.changePct());
    }

    @Test
    void parsesQuotePercentagesWithSignZeroAndRounding() throws IOException {
        String at = "|Closed at Nov 16, 2026 4:00 PM ET";
        assertEquals(new BigDecimal("3.1"), Market.parseClose("A", MONDAY, info("$1.00|+3.05%" + at, null)).changePct());
        assertEquals(new BigDecimal("-3.1"), Market.parseClose("A", MONDAY, info("$1.00|-3.05%" + at, null)).changePct());
        assertEquals(new BigDecimal("0.0"), Market.parseClose("A", MONDAY, info("$1.00|0.00%" + at, null)).changePct());
        assertEquals(new BigDecimal("0.0"), Market.parseClose("A", MONDAY, info("$1.00|-0.04%" + at, null)).changePct());
        assertEquals(new BigDecimal("12.0"), Market.parseClose("A", MONDAY, info("$1.00|12%" + at, null)).changePct());
    }

    @Test
    void anUnreadableChangeOrPriceIsAnError() {
        String at = "|Closed at Nov 16, 2026 4:00 PM ET";
        assertThrows(IOException.class, () -> Market.parseClose("A", MONDAY, info("$1.00|UNCH" + at, null)));
        assertThrows(IOException.class, () -> Market.parseClose("A", MONDAY, info("$1.00|" + at, null)));
        assertThrows(IOException.class, () -> Market.parseClose("A", MONDAY, info("N/A|1.0%" + at, null)));
    }

    @Test
    void aQuoteWithoutAClosedBlockYieldsNothingAndAnUnknownSymbolIsAnError() throws IOException {
        assertNull(Market.parseClose("NVDA", MONDAY, info(AFTER_HOURS, null)));
        assertThrows(IOException.class, () -> Market.parseClose("LKNCY", MONDAY, UNKNOWN_QUOTE));
    }

    @Test
    void aCloseDatedAfterTheRequestedDayIsRejectedWithoutFallingBackToOlderData() {
        Market market = new Market(new FakeFetcher(url -> url.contains("/info?")
                ? info(AFTER_HOURS, CLOSE) : history("11/13/2026", "162.85", "11/12/2026", "166.19")));
        IOException e = assertThrows(IOException.class, () -> market.price("NVDA", LocalDate.of(2026, 11, 13)));
        assertTrue(e.getMessage().contains("after 2026-11-13"), e.getMessage());
    }

    @Test
    void priceUsesTheQuoteCloseAndSkipsHistory() throws IOException {
        List<String> asked = new ArrayList<>();
        Market market = new Market(new FakeFetcher(url -> {
            asked.add(url);
            return info(AFTER_HOURS, CLOSE);
        }));
        assertEquals(new BigDecimal("-4.1"), market.price("NVDA", MONDAY).changePct());
        assertEquals(1, asked.size());
        assertTrue(asked.get(0).contains("/NVDA/info?assetclass=stocks"));
    }

    @Test
    void priceFallsBackToHistoryWhenNeitherQuoteHasAClosedBlock() throws IOException {
        Market market = new Market(new FakeFetcher(url -> url.contains("/info?")
                ? info(AFTER_HOURS, null) : history("11/13/2026", "162.85", "11/12/2026", "166.19")));
        Market.Price p = market.price("NVDA", MONDAY);
        assertEquals(LocalDate.of(2026, 11, 13), p.date());
        assertEquals(new BigDecimal("-2.0"), p.changePct());
    }

    @Test
    void priceTriesTheQuoteAsAnEtfAfterStocksAnswersUnknown() throws IOException {
        Market market = new Market(new FakeFetcher(url -> {
            if (url.contains("/DRAM/info?assetclass=etf")) {
                return info("$39.24|-0.13%|Nov 16, 2026 7:56 PM ET", "$39.29|-7.33%|Closed at Nov 16, 2026 4:00 PM ET");
            }
            return UNKNOWN_QUOTE;
        }));
        Market.Price p = market.price("DRAM", MONDAY);
        assertEquals(new BigDecimal("39.29"), p.close());
        assertEquals(new BigDecimal("-7.3"), p.changePct());
    }

    @Test
    void priceFailsForAnUnknownSymbolEverywhere() {
        Market market = new Market(new FakeFetcher(url -> UNKNOWN_QUOTE));
        assertThrows(IOException.class, () -> market.price("LKNCY", MONDAY));
    }

    @Test
    void mapsEarningsTimesAndHandlesEmptyDays() throws IOException {
        LocalDate day = LocalDate.of(2026, 11, 17);
        List<Market.Earning> rows = Market.parseEarnings(day, "{\"data\":{\"rows\":["
                + "{\"time\":\"time-after-hours\",\"symbol\":\"NVDA\"},"
                + "{\"time\":\"time-pre-market\",\"symbol\":\"hd\"},"
                + "{\"time\":\"time-not-supplied\",\"symbol\":\"XYZ\"}]}}");
        assertEquals(List.of(new Market.Earning("NVDA", day, "after the close"),
                new Market.Earning("HD", day, "before the open"), new Market.Earning("XYZ", day, "")), rows);
        assertEquals(List.of(), Market.parseEarnings(day, "{\"data\":{\"rows\":null}}"));
        assertThrows(IOException.class, () -> Market.parseEarnings(day, "{\"data\":null}"));
    }

    @Test
    void doesNotAskForWeekendEarnings() throws IOException {
        Market market = new Market(new FakeFetcher(url -> {
            throw new AssertionError("no request expected, got " + url);
        }));
        assertEquals(List.of(), market.earningsOn(LocalDate.of(2026, 11, 14)));
        assertEquals(List.of(), market.earningsOn(LocalDate.of(2026, 11, 15)));
    }

    private static String row(String title, String url, String created) {
        return "{\"title\":\"" + title + "\",\"url\":\"" + url + "\",\"created\":\"" + created
                + "\",\"publisher\":\"Pub\",\"primarysymbol\":\"nvda\",\"related_symbols\":[\"nvda|stocks\"]}";
    }

    @Test
    void headlinesResolveRelativeLinksAndKeepTheNewestFour() throws IOException {
        String body = "{\"data\":{\"rows\":[" + String.join(",",
                row("A", "/articles/a", "Nov 14, 2026"), row("B", "/articles/b", "Nov 16, 2026"), row("C", "/articles/c", "Nov 15, 2026"),
                row("D", "/articles/d", "Nov 15, 2026"), row("E", "/articles/e", "Nov 13, 2026"), row("F", "/articles/f", "Nov 12, 2026")) + "]}}";
        List<Market.Headline> found = Market.parseHeadlines("NVDA", LocalDate.of(2026, 11, 13), body);
        assertEquals(List.of("B", "C", "D", "A"), found.stream().map(Market.Headline::title).toList());
        assertEquals("https://www.nasdaq.com/articles/b", found.get(0).url());
        assertEquals(LocalDate.of(2026, 11, 16), found.get(0).published());
        assertEquals("Pub", found.get(0).publisher());
    }

    @Test
    void headlinesDropNonHttpsAbsoluteLinksAndUndatedRows() throws IOException {
        String body = "{\"data\":{\"rows\":[" + String.join(",",
                row("Plain", "http://example.com/a", "Nov 16, 2026"), row("Script", "javascript:alert(1)", "Nov 16, 2026"),
                row("No date", "/articles/x", "yesterday"), row("Absolute", "https://example.com/ok", "Nov 16, 2026")) + "]}}";
        List<Market.Headline> found = Market.parseHeadlines("NVDA", LocalDate.of(2026, 11, 13), body);
        assertEquals(List.of("Absolute"), found.stream().map(Market.Headline::title).toList());
    }

    @Test
    void headlinesSayWhetherTheTickerIsThePrimarySymbol() throws IOException {
        String body = "{\"data\":{\"rows\":["
                + "{\"title\":\"Own\",\"url\":\"/articles/own\",\"created\":\"Nov 16, 2026\",\"primarysymbol\":\"NVDA\",\"related_symbols\":[\"nvda|stocks\"]},"
                + "{\"title\":\"Other\",\"url\":\"/articles/other\",\"created\":\"Nov 15, 2026\",\"primarysymbol\":\"avgo\",\"related_symbols\":[\"avgo|stocks\",\"nvda|stocks\"]}"
                + "]}}";
        List<Market.Headline> found = Market.parseHeadlines("NVDA", LocalDate.of(2026, 11, 13), body);
        assertEquals(List.of(true, false), found.stream().map(Market.Headline::primary).toList());
    }

    @Test
    void headlinesIgnoreArticlesThatDoNotNameTheTicker() throws IOException {
        String body = "{\"data\":{\"rows\":[{\"title\":\"General\",\"url\":\"/articles/g\",\"created\":\"Nov 16, 2026\","
                + "\"primarysymbol\":\"avgo\",\"related_symbols\":[\"avgo|stocks\",\"nvdax|stocks\"]}]}}";
        assertEquals(List.of(), Market.parseHeadlines("NVDA", LocalDate.of(2026, 11, 13), body));
    }

    @Test
    void headlinesFailLoudlyOnAnUnreadableResponseInsteadOfReportingNoNews() {
        assertThrows(IOException.class, () -> Market.parseHeadlines("NVDA", LocalDate.of(2026, 11, 13), "{\"data\":null}"));
        Market failing = new Market(new FakeFetcher(url -> { throw new IOException("HTTP 503"); }));
        assertThrows(IOException.class, () -> failing.headlines("NVDA", LocalDate.of(2026, 11, 13)));
    }
}
