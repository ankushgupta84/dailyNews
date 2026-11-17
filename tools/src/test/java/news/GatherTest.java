package news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatherTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 11, 16);
    private static final Instant NOW = Instant.parse("2026-11-16T11:30:00Z");

    @TempDir
    Path root;
    /** URL fragment -> body. A missing entry answers like a dead site. */
    final Map<String, String> web = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws IOException {
        write("profile.json", """
                {"tickers":{"focus":["AAA","BBB","CCC","OLD"],"general":["GEN"]},
                 "learnRotation":{"monday":"Distributed systems","tuesday":"Security","wednesday":"AI","thursday":"Design",
                                  "friday":"Leadership","saturday":"Paper","sunday":"Long view"}}""");
        write("macro.json", "{\"events\":[]}");
        write("editions/index.json", "{\"dates\":[]}");
        write("sources.json", "{\"sources\":[{\"name\":\"Daily\",\"beat\":\"world\",\"feed\":\"https://feeds.test/daily\",\"schedule\":\"daily\",\"max\":5}]}");
        web.put("feeds.test/daily", feed("Fresh", "https://n.test/fresh", "Mon, 16 Nov 2026 09:00:00 GMT"));
        web.put("calendar/earnings", "{\"data\":{\"rows\":null}}");
        price("AAA", "11/13/2026", "103.00", "100.00");
        price("BBB", "11/13/2026", "97.10", "100.00");
        price("CCC", "11/13/2026", "92.00", "100.00");
        price("GEN", "11/13/2026", "150.00", "100.00");
        price("OLD", "11/06/2026", "50.00", "100.00");
    }

    private void write(String path, String content) throws IOException {
        Path file = root.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private void price(String ticker, String date, String close, String before) {
        web.put("/quote/" + ticker + "/", MarketTest.history(date, close, "11/01/2026", before));
    }

    private static String feed(String title, String link, String pubDate) {
        return "<rss><channel><item><title>" + title + "</title><link>" + link + "</link><pubDate>" + pubDate
                + "</pubDate><description>About " + title + "</description></item></channel></rss>";
    }

    private Fetcher fetcher() {
        return new FakeFetcher(url -> {
            // The most specific (longest) matching fragment wins, so a general entry can act as a default.
            String best = null;
            for (String key : web.keySet()) {
                if (url.contains(key) && (best == null || key.length() > best.length())) {
                    best = key;
                }
            }
            if (best == null) {
                throw new IOException("HTTP 404");
            }
            return web.get(best);
        });
    }

    private JsonNode build(LocalDate date) throws IOException {
        return Gather.build(root, date, NOW, fetcher());
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(field == null ? n.asText() : n.path(field).asText()));
        return out;
    }

    @Test
    void picksMoversFromFocusTickersAtOrBeyondThreePercent() throws IOException {
        JsonNode brief = build(MONDAY);
        assertEquals("Nov 13 close", brief.path("asOf").asText());
        assertEquals("2026-11-13", brief.path("asOfDate").asText());
        // CCC -8.0, AAA +3.0 (exactly on the threshold); BBB -2.9 is below it; GEN +50 is not a focus ticker.
        assertEquals(List.of("CCC", "AAA"), texts(brief.path("movers"), "ticker"));
        assertEquals("-8.0", brief.path("movers").get(0).path("changePct").asText());
        assertEquals(List.of("GEN", "CCC", "AAA", "BBB"), texts(brief.path("prices"), "ticker"));
    }

    @Test
    void reportsStaleAndUnknownTickersAsMissingInsteadOfGuessing() throws IOException {
        web.remove("/quote/BBB/");
        JsonNode brief = build(MONDAY);
        List<String> missing = texts(brief.path("pricesMissing"), null);
        assertTrue(missing.contains("BBB") && missing.contains("OLD"), missing.toString());
        assertEquals(List.of("GEN", "CCC", "AAA"), texts(brief.path("prices"), "ticker"));
    }

    @Test
    void writesAProblemWhenNoPricesCanBeFetched() throws IOException {
        web.keySet().removeIf(k -> k.startsWith("/quote/"));
        JsonNode brief = build(MONDAY);
        assertEquals("unavailable", brief.path("asOf").asText());
        assertTrue(brief.path("asOfDate").isMissingNode());
        assertEquals(0, brief.path("movers").size());
        assertTrue(brief.path("problems").get(0).asText().contains("No prices"));
    }

    @Test
    void listsFocusEarningsForTheWeekAndThoseJustReported() throws IOException {
        web.remove("calendar/earnings");
        web.put("calendar/earnings?date=2026-11-13", "{\"data\":{\"rows\":[{\"time\":\"time-after-hours\",\"symbol\":\"BBB\"},{\"time\":\"\",\"symbol\":\"GEN\"}]}}");
        web.put("calendar/earnings?date=2026-11-17", "{\"data\":{\"rows\":[{\"time\":\"time-after-hours\",\"symbol\":\"AAA\"},{\"time\":\"\",\"symbol\":\"ZZZ\"}]}}");
        web.put("calendar/earnings?date=2026-11-23", "{\"data\":{\"rows\":[{\"time\":\"time-pre-market\",\"symbol\":\"CCC\"}]}}");
        web.put("calendar/earnings?date=2026-11-24", "{\"data\":{\"rows\":[{\"time\":\"\",\"symbol\":\"AAA\"}]}}"); // day 8: outside the window
        web.put("calendar/earnings", "{\"data\":{\"rows\":null}}");
        JsonNode brief = build(MONDAY);
        assertEquals(List.of("BBB"), texts(brief.path("reportedYesterday"), null));
        assertEquals(List.of("AAA", "CCC"), texts(brief.path("earnings"), "ticker"));
        assertEquals(List.of("2026-11-17", "2026-11-23"), texts(brief.path("earnings"), "date"));
        assertEquals("after the close", brief.path("earnings").get(0).path("time").asText());
    }

    @Test
    void dropsThePartialCalendarWhenAnyEarningsDayFails() throws IOException {
        web.remove("calendar/earnings");
        web.put("calendar/earnings?date=2026-11-17", "{\"data\":{\"rows\":[{\"time\":\"\",\"symbol\":\"AAA\"}]}}");
        JsonNode brief = build(MONDAY); // every other day answers HTTP 404
        assertEquals(0, brief.path("earnings").size());
        assertTrue(brief.path("problems").toString().contains("Earnings calendar unavailable"));
    }

    @Test
    void recordsFailedSourcesAndKeepsGoing() throws IOException {
        write("sources.json", """
                {"sources":[
                 {"name":"Daily","beat":"world","feed":"https://feeds.test/daily","schedule":"daily","max":5},
                 {"name":"Down","beat":"world","feed":"https://feeds.test/down","schedule":"daily","max":5},
                 {"name":"Broken","beat":"world","feed":"https://feeds.test/broken","schedule":"daily","max":5},
                 {"name":"Quiet","beat":"world","feed":"https://feeds.test/quiet","schedule":"daily","max":5}]}""");
        web.put("feeds.test/broken", "<html>please enable JavaScript</html>");
        web.put("feeds.test/quiet", feed("Stale", "https://n.test/stale", "Mon, 02 Nov 2026 09:00:00 GMT"));
        JsonNode brief = build(MONDAY);
        assertEquals(2, brief.path("sourcesReached").asInt());
        assertEquals(List.of("Down", "Broken"), texts(brief.path("sourcesFailed"), "source"));
        assertEquals("HTTP 404", brief.path("sourcesFailed").get(0).path("reason").asText());
        assertEquals(List.of("Fresh"), texts(brief.path("candidates"), "title"));
        assertEquals("world", brief.path("candidates").get(0).path("beat").asText());
        assertEquals("About Fresh", brief.path("candidates").get(0).path("excerpt").asText());
    }

    @Test
    void refusesToWriteABriefWhenNothingCanBeReached() {
        web.remove("feeds.test/daily");
        IOException e = assertThrows(IOException.class, () -> build(MONDAY));
        assertTrue(e.getMessage().contains("no source could be reached"));
    }

    @Test
    void readsWeekdaySourcesOnlyOnTheirDayWithAWeekOfLookback() throws IOException {
        write("sources.json", """
                {"sources":[
                 {"name":"Daily","beat":"world","feed":"https://feeds.test/daily","schedule":"daily","max":5},
                 {"name":"Mondays","beat":"career","feed":"https://feeds.test/monday","schedule":"monday","max":5},
                 {"name":"Fridays","beat":"career","feed":"https://feeds.test/friday","schedule":"friday","max":5}]}""");
        web.put("feeds.test/monday", feed("Five days old", "https://n.test/weekly", "Wed, 11 Nov 2026 09:00:00 GMT"));
        web.put("feeds.test/friday", feed("Not today", "https://n.test/friday", "Mon, 16 Nov 2026 09:00:00 GMT"));
        JsonNode brief = build(MONDAY);
        assertEquals(List.of("Fresh", "Five days old"), texts(brief.path("candidates"), "title"));
        assertEquals(2, brief.path("sourcesReached").asInt());
    }

    @Test
    void setsKindAndLearnTrackFromTheDate() throws IOException {
        assertEquals("weekday", build(MONDAY).path("kind").asText());
        assertEquals("Distributed systems", build(MONDAY).path("learnTrack").asText());
        JsonNode saturday = build(LocalDate.of(2026, 11, 21));
        assertEquals("saturday", saturday.path("kind").asText());
        assertEquals("Paper", saturday.path("learnTrack").asText());
        assertEquals("sunday", build(LocalDate.of(2026, 11, 22)).path("kind").asText());
    }

    @Test
    void failsLoudlyWhenTheRotationLacksToday() throws IOException {
        write("profile.json", "{\"tickers\":{\"focus\":[],\"general\":[]},\"learnRotation\":{}}");
        assertThrows(IOException.class, () -> build(MONDAY));
    }

    @Test
    void keepsMacroEventsInsideATwoWeekWindowAcrossTheYearEnd() throws IOException {
        write("macro.json", """
                {"events":[{"date":"2026-12-27","event":"Too early"},{"date":"2026-12-28","event":"First day"},
                           {"date":"2027-01-11","event":"Last day"},{"date":"2027-01-12","event":"Too late"}]}""");
        JsonNode brief = build(LocalDate.of(2026, 12, 28));
        assertEquals(List.of("First day", "Last day"), texts(brief.path("macro"), "event"));
    }

    @Test
    void carriesHeadlinesFromTheLastThreeEditionsAndEveryLearnTitle() throws IOException {
        write("editions/index.json", "{\"dates\":[\"2026-11-16\",\"2026-11-15\",\"2026-11-14\",\"2026-11-13\",\"2026-10-30\"]}");
        for (String d : List.of("2026-11-16", "2026-11-15", "2026-11-14", "2026-11-13", "2026-10-30")) {
            write("editions/" + d.substring(0, 4) + "/" + d.substring(5, 7) + "/" + d + ".json",
                    "{\"frontPage\":[{\"headline\":\"Front " + d + "\",\"url\":\"https://n.test/f" + d + "\"}],"
                            + "\"sections\":[{\"items\":[{\"headline\":\"Item " + d + "\",\"url\":\"https://n.test/i" + d + "\"}]}],"
                            + "\"learn\":{\"title\":\"Learn " + d + "\"}}");
        }
        JsonNode brief = build(MONDAY); // an edition dated today must not count as "recent"
        assertEquals(List.of("Front 2026-11-13", "Item 2026-11-13", "Front 2026-11-14", "Item 2026-11-14", "Front 2026-11-15", "Item 2026-11-15"),
                texts(brief.path("recentHeadlines"), "headline"));
        assertEquals(5, brief.path("learnTitlesUsed").size());
    }

    @Test
    void retriesAFeedOnceBeforeReportingIt() throws IOException {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Fetcher flaky = new FakeFetcher(url -> {
            if (url.contains("feeds.test/daily") && calls.incrementAndGet() == 1) {
                throw new IOException("HTTP 502");
            }
            return fetcher().get(url);
        });
        JsonNode brief = Gather.build(root, MONDAY, NOW, flaky);
        assertEquals(2, calls.get());
        assertEquals(1, brief.path("sourcesReached").asInt());
        assertEquals(0, brief.path("sourcesFailed").size());
    }

    @Test
    void readsManySourcesConcurrentlyWithoutLosingOrReorderingAny() throws IOException {
        StringBuilder sources = new StringBuilder("{\"sources\":[");
        for (int i = 0; i < 60; i++) {
            sources.append(i == 0 ? "" : ",").append("{\"name\":\"S").append(i).append("\",\"beat\":\"world\",\"feed\":\"https://feeds.test/s")
                    .append(i).append("x\",\"schedule\":\"daily\",\"max\":1}");
            if (i % 7 != 0) { // every seventh source is down
                web.put("feeds.test/s" + i + "x", feed("T" + i, "https://n.test/" + i, "Mon, 16 Nov 2026 09:00:00 GMT"));
            }
        }
        write("sources.json", sources.append("]}").toString());
        Fetcher slow = new FakeFetcher(url -> {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(15)); // finish out of order
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return fetcher().get(url);
        });
        for (int run = 0; run < 5; run++) {
            JsonNode brief = Gather.build(root, MONDAY, NOW, slow);
            List<String> expected = new ArrayList<>();
            List<String> down = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                (i % 7 == 0 ? down : expected).add(i % 7 == 0 ? "S" + i : "T" + i);
            }
            assertEquals(expected, texts(brief.path("candidates"), "title"));
            assertEquals(down, texts(brief.path("sourcesFailed"), "source"));
            assertEquals(51, brief.path("sourcesReached").asInt());
        }
    }

    @Test
    void keepsOnlyTheFirstCandidateForAUrlAcrossSources() throws IOException {
        write("sources.json", """
                {"sources":[
                 {"name":"First","beat":"world","feed":"https://feeds.test/a","schedule":"daily","max":5},
                 {"name":"Second","beat":"markets","feed":"https://feeds.test/b","schedule":"daily","max":5}]}""");
        web.put("feeds.test/a", feed("Shared", "https://n.test/shared", "Mon, 16 Nov 2026 09:00:00 GMT"));
        web.put("feeds.test/b", "<rss><channel>"
                + "<item><title>Shared again</title><link>https://n.test/shared</link><pubDate>Mon, 16 Nov 2026 10:00:00 GMT</pubDate></item>"
                + "<item><title>Only here</title><link>https://n.test/only</link><pubDate>Mon, 16 Nov 2026 08:00:00 GMT</pubDate></item>"
                + "</channel></rss>");
        JsonNode brief = build(MONDAY);
        assertEquals(List.of("Shared", "Only here"), texts(brief.path("candidates"), "title"));
        assertEquals(List.of("First", "Second"), texts(brief.path("candidates"), "source"));
        assertEquals(2, brief.path("sourcesReached").asInt());
    }

    @Test
    void dropsItemsStampedMoreThanAnHourAfterNow() throws IOException {
        web.put("feeds.test/daily", "<rss><channel>"
                + "<item><title>Within slack</title><link>https://n.test/ok</link><pubDate>Mon, 16 Nov 2026 12:30:00 GMT</pubDate></item>"
                + "<item><title>Wrong year</title><link>https://n.test/late</link><pubDate>Mon, 16 Nov 2026 12:31:00 GMT</pubDate></item>"
                + "<item><title>Next week</title><link>https://n.test/next</link><pubDate>Mon, 23 Nov 2026 09:00:00 GMT</pubDate></item>"
                + "</channel></rss>");
        assertEquals(List.of("Within slack"), texts(build(MONDAY).path("candidates"), "title"));
    }

    @Test
    void lookbackDaysOverridesTheDefaultWindow() throws IOException {
        write("sources.json", """
                {"sources":[
                 {"name":"Default","beat":"world","feed":"https://feeds.test/d","schedule":"daily","max":5},
                 {"name":"Monthly","beat":"live-well","feed":"https://feeds.test/m","schedule":"daily","max":5,"lookbackDays":30}]}""");
        String tenDaysOld = "Fri, 06 Nov 2026 09:00:00 GMT";
        web.put("feeds.test/d", feed("Default window", "https://n.test/d", tenDaysOld));
        web.put("feeds.test/m", feed("Thirty day window", "https://n.test/m", tenDaysOld));
        assertEquals(List.of("Thirty day window"), texts(build(MONDAY).path("candidates"), "title"));
    }

    @Test
    void rejectsANonPositiveOrNonIntegerLookbackNamingTheSource() throws IOException {
        for (String bad : List.of("0", "-3", "1.5", "\"30\"")) {
            write("sources.json", "{\"sources\":[{\"name\":\"Bad Source\",\"beat\":\"world\",\"feed\":\"https://feeds.test/daily\","
                    + "\"schedule\":\"friday\",\"max\":5,\"lookbackDays\":" + bad + "}]}");
            IOException e = assertThrows(IOException.class, () -> build(MONDAY)); // fails even though it is not Friday
            assertTrue(e.getMessage().contains("Bad Source") && e.getMessage().contains("lookbackDays"), e.getMessage());
        }
    }

    private static String news(String... titleUrlDateAndPublisher) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < titleUrlDateAndPublisher.length; i += 4) {
            rows.append(i == 0 ? "" : ",").append("{\"title\":\"").append(titleUrlDateAndPublisher[i])
                    .append("\",\"url\":\"").append(titleUrlDateAndPublisher[i + 1])
                    .append("\",\"created\":\"").append(titleUrlDateAndPublisher[i + 2])
                    .append("\",\"publisher\":\"").append(titleUrlDateAndPublisher[i + 3])
                    .append("\",\"primarysymbol\":\"\",\"related_symbols\":[\"ccc|stocks\",\"aaa|stocks\"]}");
        }
        return "{\"data\":{\"rows\":[" + rows + "]}}";
    }

    @Test
    void attachesRecentHeadlinesToEachMover() throws IOException {
        web.put("articlebysymbol?q=CCC%7CSTOCKS", news(
                "CCC falls on guidance", "/articles/ccc-falls", "Nov 16, 2026", "Barchart",
                "CCC older note", "/articles/ccc-old", "Nov 13, 2026", "Fool",
                "CCC before the session", "/articles/ccc-ancient", "Nov 12, 2026", "Fool"));
        web.put("articlebysymbol?q=AAA%7CSTOCKS", news("AAA jumps", "/articles/aaa", "Nov 14, 2026", "Fool"));
        JsonNode news = build(MONDAY).path("moverNews");
        assertEquals(List.of("CCC", "AAA"), texts(news, "ticker"));
        JsonNode ccc = news.get(0).path("headlines");
        // The session is Nov 13 (the fixtures' close date): headlines from that day on, not the days before.
        assertEquals(List.of("CCC falls on guidance", "CCC older note"), texts(ccc, "title"));
        assertEquals("https://www.nasdaq.com/articles/ccc-falls", ccc.get(0).path("url").asText());
        assertEquals("2026-11-16", ccc.get(0).path("published").asText());
        assertEquals("Barchart", ccc.get(0).path("publisher").asText());
        assertFalse(ccc.get(0).path("primary").asBoolean(true)); // the fixture names CCC only as a related symbol
    }

    @Test
    void writesMoverHeadlinesAsPrimaryWhenTheTickerIsTheArticlesOwn() throws IOException {
        web.put("articlebysymbol?q=CCC%7CSTOCKS", "{\"data\":{\"rows\":[{\"title\":\"CCC falls\",\"url\":\"/articles/c\","
                + "\"created\":\"Nov 16, 2026\",\"primarysymbol\":\"ccc\",\"related_symbols\":[\"ccc|stocks\"]}]}}");
        JsonNode h = build(MONDAY).path("moverNews").get(0).path("headlines").get(0);
        assertTrue(h.path("primary").asBoolean(false));
    }

    @Test
    void doesNotRegenerateABriefWhoseEditionExists() throws IOException {
        LocalDate day = LocalDate.of(2026, 11, 16);
        assertFalse(Gather.editionExists(root, day));
        Path edition = new Editions(root).file(day.toString());
        Files.createDirectories(edition.getParent());
        Files.writeString(edition, "{}");
        assertTrue(Gather.editionExists(root, day));
        assertFalse(Gather.editionExists(root, day.plusDays(1)));
    }

    @Test
    void moverNewsStartsAtTheSessionDateNotAFixedNumberOfDaysBack() throws IOException {
        // Tuesday's brief still describes Friday's session; three days back would be Saturday and drop Friday's wrap.
        web.put("articlebysymbol?q=CCC%7CSTOCKS", news(
                "Friday wrap", "/articles/fri", "Nov 13, 2026", "Barchart",
                "Thursday note", "/articles/thu", "Nov 12, 2026", "Fool"));
        JsonNode brief = build(LocalDate.of(2026, 11, 17));
        assertEquals("2026-11-13", brief.path("asOfDate").asText());
        assertEquals(List.of("Friday wrap"), texts(brief.path("moverNews").get(0).path("headlines"), "title"));
    }

    @Test
    void usesTheOfficialCloseFromTheQuoteEndpointWhenItIsAvailable() throws IOException {
        // History still shows the prior session (-8.0%); the quote already carries Monday's close.
        web.put("/quote/CCC/info", MarketTest.info("$91.00|-9.00%|Nov 16, 2026 7:59 PM ET", "$92.00|-8.04%|Closed at Nov 16, 2026 4:00 PM ET"));
        for (String t : List.of("AAA", "BBB", "GEN")) {
            web.put("/quote/" + t + "/info", MarketTest.info("$1.00|+1.00%|Closed at Nov 16, 2026 4:00 PM ET", null));
        }
        web.put("/quote/OLD/info", MarketTest.info("$1.00|+1.00%|Closed at Nov 16, 2026 4:00 PM ET", null));
        JsonNode brief = build(MONDAY);
        assertEquals("Nov 16 close", brief.path("asOf").asText());
        assertEquals("2026-11-16", brief.path("asOfDate").asText());
        assertEquals(List.of("CCC"), texts(brief.path("movers"), "ticker"));
        assertEquals("-8.0", brief.path("movers").get(0).path("changePct").asText());
    }

    @Test
    void oneMoversFailedLookupBecomesAProblemNotAFailedBrief() throws IOException {
        // CCC has no mocked news URL, so both of its lookups answer HTTP 404.
        web.put("articlebysymbol?q=AAA%7CSTOCKS", news("AAA jumps", "/articles/aaa", "Nov 14, 2026", "Fool"));
        JsonNode brief = build(MONDAY);
        JsonNode news = brief.path("moverNews");
        assertEquals(0, news.get(0).path("headlines").size());
        assertEquals(1, news.get(1).path("headlines").size());
        assertEquals(1, brief.path("problems").size());
        String problem = brief.path("problems").get(0).asText();
        assertTrue(problem.contains("CCC") && problem.contains("HTTP 404"), problem);
    }

    @Test
    void triesEtfNewsWhenTheStockLookupFindsNothingAboutTheTicker() throws IOException {
        // For an unknown symbol Nasdaq returns the day's general news; it must be ignored.
        web.put("articlebysymbol?q=CCC%7CSTOCKS", "{\"data\":{\"rows\":[{\"title\":\"Market wrap\",\"url\":\"/articles/wrap\","
                + "\"created\":\"Nov 16, 2026\",\"primarysymbol\":\"\",\"related_symbols\":[\"spy|etf\"]}]}}");
        web.put("articlebysymbol?q=CCC%7CETF", news("CCC fund flows", "/articles/ccc-etf", "Nov 16, 2026", "BNK"));
        web.put("articlebysymbol?q=AAA%7CSTOCKS", "{\"data\":{\"rows\":[]}}");
        web.put("articlebysymbol?q=AAA%7CETF", "{\"data\":{\"rows\":[]}}");
        JsonNode brief = build(MONDAY);
        assertEquals(List.of("CCC fund flows"), texts(brief.path("moverNews").get(0).path("headlines"), "title"));
        assertEquals(0, brief.path("moverNews").get(1).path("headlines").size()); // no news is not a failure
        assertEquals(0, brief.path("problems").size());
    }
}
