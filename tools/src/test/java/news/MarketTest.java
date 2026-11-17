package news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(2, asked.size());
        assertThrows(IOException.class, () -> market.price("LKNCY", LocalDate.of(2026, 11, 16)));
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
