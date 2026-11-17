package news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ValidateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DATE = "2026-11-17";

    @TempDir
    Path root;
    ObjectNode edition;
    ObjectNode brief;

    @BeforeEach
    void setUp() throws IOException {
        // The real schema, so the tests fail if the contract and the validator drift apart.
        Files.createDirectories(root.resolve("schema"));
        Files.copy(Path.of("../schema/edition.schema.json"), root.resolve("schema/edition.schema.json"));
        Files.writeString(root.resolve("profile.json"),
                "{\"tickers\":{\"focus\":[\"NVDA\",\"AVGO\",\"CRWD\",\"VST\",\"ANET\",\"NET\"],\"general\":[\"MP\"]}}");
        Files.writeString(root.resolve("sources.json"), "{\"sources\":[{\"name\":\"The Wall Street Journal\"},{\"name\":\"The Record\"}]}");
        edition = (ObjectNode) JSON.readTree(resource("edition-valid.json"));
        brief = (ObjectNode) JSON.readTree(resource("brief-valid.json"));
        writeIndex("[\"" + DATE + "\"]");
    }

    private static String resource(String name) throws IOException {
        try (var in = ValidateTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void writeIndex(String datesJson) throws IOException {
        Files.createDirectories(root.resolve("editions"));
        Files.writeString(root.resolve("editions/index.json"), "{\"dates\":" + datesJson + "}");
    }

    private List<String> check(Fetcher fetcher) throws IOException {
        Path file = new Editions(root).file(DATE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.writeValueAsString(edition));
        Files.createDirectories(root.resolve("brief"));
        Files.writeString(root.resolve("brief/" + DATE + ".json"), JSON.writeValueAsString(brief));
        return Validate.check(root, DATE, fetcher);
    }

    /** Applies one change to the valid edition and expects exactly one kind of complaint. */
    private void rejects(String expected, Consumer<ObjectNode> change) throws IOException {
        change.accept(edition);
        List<String> problems = check(null);
        assertTrue(problems.stream().anyMatch(p -> p.contains(expected)),
                "expected a problem containing \"" + expected + "\" but got " + problems);
    }

    private ObjectNode firstStory() {
        return (ObjectNode) edition.path("frontPage").get(0);
    }

    private ObjectNode watchlist() {
        return (ObjectNode) edition.path("watchlist");
    }


    private void addCandidate(String url, String title, String excerpt) {
        ((ArrayNode) brief.path("candidates")).addObject().put("beat", "world").put("source", "Sample")
                .put("title", title).put("url", url).put("published", "2026-11-17T01:00:00Z").put("excerpt", excerpt);
    }

    private ObjectNode candidate(String url) {
        for (var c : brief.path("candidates")) {
            if (c.path("url").asText().equals(url)) return (ObjectNode) c;
        }
        throw new AssertionError("no candidate " + url);
    }

    private static final String LEAD = "https://example.com/sample-lead";
    private static final String SECOND = "https://example.com/sample-2";

    private ObjectNode deepDive() {
        addCandidate("https://example.com/dd", "SAMPLE: Results", "Revenue rose. \"We saw strong demand\" said the CEO.");
        brief.putArray("reportedYesterday").add("NVDA");
        return watchlist().putObject("earningsDeepDive").put("ticker", "NVDA").put("results", "r").put("guidance", "g")
                .put("url", "https://example.com/dd");
    }

    @Test
    void rejectsStoryUrlNotInBrief() throws IOException {
        rejects("link not in the brief's candidates or moverNews at frontPage[0]: https://example.com/invented",
                e -> firstStory().put("url", "https://example.com/invented"));
    }

    @Test
    void rejectsMoverTipAndLearnUrlsNotInBrief() throws IOException {
        edition.path("watchlist").path("movers").forEach(m -> ((ObjectNode) m).put("url", "https://example.com/x1"));
        ((ObjectNode) edition.path("liveWell").path("health")).put("url", "https://example.com/x2");
        ((ObjectNode) edition.path("learn")).put("url", "https://example.com/x3");
        List<String> problems = check(null);
        for (String where : List.of("watchlist.movers[0]", "liveWell.health", "learn")) {
            assertTrue(problems.stream().anyMatch(p -> p.contains("link not in the brief") && p.contains(" at " + where + ":")), problems.toString());
        }
    }

    @Test
    void rejectsRewrittenHeadlineAndNamesTheOriginal() throws IOException {
        rejects("must be the source's headline, copied exactly: \"SAMPLE: The day's biggest story takes the lead slot in large type\"",
                e -> firstStory().put("headline", "The day's biggest story leads"));
    }

    @Test
    void acceptsExactHeadlineIgnoringOuterWhitespace() throws IOException {
        firstStory().put("headline", "  SAMPLE: The day's biggest story takes the lead slot in large type ");
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsRewrittenSectionHeadlineLongReadAndListenTitle() throws IOException {
        ((ObjectNode) edition.path("sections").get(0).path("items").get(0)).put("headline", "Changed");
        edition.put("kind", "saturday").put("date", "2026-11-17"); // checks below do not depend on weekday rules
        ObjectNode weekend = edition.putObject("weekend").put("weekInReview", "x");
        weekend.putArray("longReads").addObject().put("headline", "Changed").put("summary", "s")
                .put("url", "https://example.com/m2").put("source", "Money Stuff");
        weekend.putObject("listen").put("title", "Changed").put("summary", "s").put("url", "https://example.com/m1");
        List<String> problems = check(null);
        for (String where : List.of("sections[0].items[0].headline", "weekend.longReads[0].headline", "weekend.listen.title")) {
            assertTrue(problems.stream().anyMatch(p -> p.startsWith(where + " must be")), problems.toString());
        }
    }

    @Test
    void rejectsNumberInSummaryThatTheExcerptLacks() throws IOException {
        rejects("ungrounded figure 9999 at frontPage[0].summary", e -> firstStory().put("summary", "It cost 9999 dollars."));
    }

    @Test
    void acceptsCommaVariantOfAGroundedNumber() throws IOException {
        candidate(LEAD).put("excerpt", "The company said 1200 jobs will go.");
        assertEquals(List.of(), check(null));
        firstStory().put("summary", "The company said 1,200 jobs will go and 4.12 percent.");
        candidate(LEAD).put("excerpt", "The company said 1200 jobs will go, 4.12 percent.");
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsNumberFoundOnlyInAnotherCandidatesExcerpt() throws IOException {
        candidate(SECOND).put("excerpt", "A flaw rated 5150 was disclosed.");
        rejects("ungrounded figure 5150 at frontPage[0].summary", e -> firstStory().put("summary", "A flaw rated 5150."));
    }

    @Test
    void rejectsUngroundedNumberInWhyItMatters() throws IOException {
        rejects("ungrounded figure 42 at frontPage[0].whyItMatters", e -> firstStory().put("whyItMatters", "Worth 42 dollars."));
    }

    @Test
    void rejectsUngroundedNumberInTipAndBearCase() throws IOException {
        ((ObjectNode) edition.path("liveWell").path("health")).put("tip", "Walk 77 minutes.");
        ((ObjectNode) watchlist().path("bearCase")).put("summary", "Spend fell 88 percent.");
        List<String> problems = check(null);
        assertTrue(problems.stream().anyMatch(p -> p.contains("figure 77 at liveWell.health.tip")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("figure 88 at watchlist.bearCase.summary")), problems.toString());
    }

    @Test
    void messageNamesTheUrl() throws IOException {
        rejects(LEAD, e -> firstStory().put("summary", "It cost 9999 dollars."));
    }

    @Test
    void doesNotCheckFiguresInHeadlinesOrTheUrlItself() throws IOException {
        candidate(LEAD).put("title", "Top 10 things in 2026");
        firstStory().put("headline", "Top 10 things in 2026");
        assertEquals(List.of(), check(null));
    }

    @Test
    void learnBodyMayCarryAnyFigures() throws IOException {
        ((ObjectNode) edition.path("learn")).put("body", edition.path("learn").path("body").asText() + "\n\nIt took 31337 requests in 2048 ms.");
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsCalendarAndWatchlistNotesWithNumbersAbsentFromTheBrief() throws IOException {
        ((ObjectNode) edition.path("calendar").get(0)).put("note", "NVDA reports 7777 things");
        ((ObjectNode) watchlist().path("earnings").get(0)).put("note", "after 8888");
        ((ObjectNode) watchlist().path("macro").get(0)).put("note", "watch 6666");
        List<String> problems = check(null);
        for (String n : List.of("7777 at calendar[0].note", "8888 at watchlist.earnings[0].note", "6666 at watchlist.macro[0].note")) {
            assertTrue(problems.stream().anyMatch(p -> p.contains("figure " + n)), problems.toString());
        }
    }

    @Test
    void acceptsCalendarNoteNumberFoundSomewhereInTheBrief() throws IOException {
        ((ObjectNode) edition.path("calendar").get(0)).put("note", "NVDA reports 1,200 times");
        candidate(SECOND).put("excerpt", "1200");
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsDeepDiveQuoteNotInSourceText() throws IOException {
        deepDive().putArray("quotes").add("We saw weak demand");
        List<String> problems = check(null);
        assertTrue(problems.stream().anyMatch(p -> p.contains("quote at watchlist.earningsDeepDive.quotes[0] is not in the source text for https://example.com/dd")), problems.toString());
    }

    @Test
    void acceptsVerbatimDeepDiveQuote() throws IOException {
        deepDive().putArray("quotes").add("We saw strong demand");
        assertEquals(List.of(), check(null));
    }

    @Test
    void groundsMoverReasonInItsMoverNewsHeadline() throws IOException {
        ((ObjectNode) brief.path("moverNews").get(0).path("headlines").get(0)).put("title", "Chipmakers fell 3% as AI stocks slid");
        ((ObjectNode) watchlist().path("movers").get(0)).put("reason", "Sector-wide: chipmakers fell 3%.");
        assertEquals(List.of(), check(null));
        ((ObjectNode) watchlist().path("movers").get(0)).put("reason", "Sector-wide: chipmakers fell 4%.");
        List<String> problems = check(null);
        assertTrue(problems.stream().anyMatch(p -> p.contains("figure 4 at watchlist.movers[0].reason")), problems.toString());
    }

    @Test
    void acceptsAMoverLinkedToItsOwnHeadline() throws IOException {
        assertEquals("https://example.com/apld", watchlist().path("movers").get(0).path("url").asText());
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsAMoverLinkedToAnotherTickersHeadline() throws IOException {
        rejects("mover APLD links https://example.com/ionq",
                e -> ((ObjectNode) e.path("watchlist").path("movers").get(0)).put("url", "https://example.com/ionq"));
    }

    @Test
    void rejectsAMoverLinkedToAPlainCandidate() throws IOException {
        rejects("mover APLD links " + LEAD,
                e -> ((ObjectNode) e.path("watchlist").path("movers").get(0)).put("url", LEAD));
    }

    @Test
    void rejectsLearnPieceThatRepeatsAStoryUrl() throws IOException {
        rejects("learn.url repeats", e -> ((ObjectNode) e.path("learn")).put("url", firstStory().path("url").asText()));
    }

    @Test
    void acceptsTheSampleEdition() throws IOException {
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsMissingRequiredField() throws IOException {
        rejects("schema", e -> e.remove("learn"));
    }

    @Test
    void rejectsUnknownSectionId() throws IOException {
        rejects("schema", e -> ((ObjectNode) e.path("sections").get(0)).put("id", "sports"));
    }

    @Test
    void rejectsSectionsOutOfOrder() throws IOException {
        rejects("schema", e -> {
            ArrayNode sections = (ArrayNode) e.path("sections");
            var first = sections.remove(0);
            sections.add(first);
        });
    }

    @Test
    void rejectsNonHttpsUrl() throws IOException {
        rejects("schema", e -> firstStory().put("url", "http://example.com/a"));
    }

    @Test
    void rejectsJavascriptUrl() throws IOException {
        rejects("schema", e -> firstStory().put("url", "javascript:alert(1)"));
    }

    @Test
    void rejectsAnyOwnedField() throws IOException {
        rejects("schema", e -> ((ObjectNode) watchlist().path("movers").get(0)).put("owned", true));
    }

    @Test
    void rejectsDateThatDiffersFromFileName() throws IOException {
        rejects("does not match file name", e -> e.put("date", "2026-11-18"));
    }

    @Test
    void rejectsKindThatDiffersFromWeekday() throws IOException {
        rejects("kind is saturday", e -> e.put("kind", "saturday"));
    }

    @Test
    void rejectsWeekendBlockOnWeekday() throws IOException {
        rejects("weekend block on a weekday", e -> e.putObject("weekend").put("weekInReview", "x").putArray("longReads"));
    }

    @Test
    void rejectsEditionAbsentFromIndex() throws IOException {
        writeIndex("[]");
        rejects("does not list", e -> { });
    }

    @Test
    void rejectsDuplicateDatesInIndex() throws IOException {
        writeIndex("[\"" + DATE + "\",\"" + DATE + "\"]");
        rejects("lists a date twice", e -> { });
    }

    @Test
    void rejectsUnsortedIndexAndMissingFiles() throws IOException {
        writeIndex("[\"2026-11-16\",\"" + DATE + "\"]");
        List<String> problems = check(null);
        assertTrue(problems.stream().anyMatch(p -> p.contains("not sorted newest first")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("2026-11-16 but its file is missing")), problems.toString());
    }

    @Test
    void rejectsOverLongEdition() throws IOException {
        // Fills every list to its schema cap with maximum-length prose: the most the schema allows.
        rejects("words; the limit is", e -> {
            String long600 = "word ".repeat(110).strip(); // under the 600-character field cap
            ArrayNode front = (ArrayNode) e.path("frontPage");
            while (front.size() < 8) {
                front.add(front.get(0).deepCopy());
            }
            for (var story : front) {
                ((ObjectNode) story).put("summary", long600);
            }
            for (var section : e.path("sections")) {
                ArrayNode items = (ArrayNode) section.path("items");
                while (items.size() < 6) {
                    items.add(front.get(0).deepCopy());
                }
                for (var item : items) {
                    ((ObjectNode) item).put("summary", long600);
                }
            }
            ((ObjectNode) e.path("learn")).put("body", "word ".repeat(790).strip());
            ArrayNode movers = (ArrayNode) e.path("watchlist").path("movers");
            while (movers.size() < 16) {
                movers.add(movers.get(0).deepCopy());
            }
            for (var mover : movers) {
                ((ObjectNode) mover).put("reason", long600);
            }
        });
    }

    @Test
    void rejectsOwnershipLanguage() throws IOException {
        rejects("ownership language \"your portfolio\"", e -> firstStory().put("whyItMatters", "This lifts your portfolio."));
    }

    @Test
    void rejectsYouOwn() throws IOException {
        rejects("ownership language \"You own\"", e -> firstStory().put("summary", "You own a lot of this one."));
    }

    @Test
    void allowsCompanyNamesAndOrdinaryUsesOfTheSameWords() throws IOException {
        firstStory().put("summary", "MARA Holdings widened its product portfolio; Berkshire cut its position in the stock.");
        assertEquals(List.of(), check(null));
    }

    @Test
    void acceptsAnEmptyLiveWellAndAPartialOne() throws IOException {
        edition.putObject("liveWell");
        assertEquals(List.of(), check(null));
        ((ObjectNode) edition.path("liveWell")).putObject("eating").put("tip", "t").put("url", "https://example.com/eating");
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsSourceSpelledDifferentlyFromSourcesJson() throws IOException {
        rejects("source \"the wall  street journal\" should be spelled \"The Wall Street Journal\"",
                e -> firstStory().put("source", "the wall  street journal"));
    }

    @Test
    void allowsSourceNamesThatMatchNothingInSourcesJson() throws IOException {
        firstStory().put("source", "Reuters");
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsSameStoryTwice() throws IOException {
        rejects("appears twice", e -> ((ObjectNode) e.path("sections").get(0).path("items").get(0))
                .put("url", firstStory().path("url").asText()));
    }

    @Test
    void rejectsMoverNumberThatDiffersFromBrief() throws IOException {
        rejects("changePct -9.5 differs from the brief's -9.0", e -> ((ObjectNode) watchlist().path("movers").get(0)).put("changePct", -9.5));
    }

    @Test
    void acceptsSameNumberWrittenDifferently() throws IOException {
        ((ObjectNode) brief.path("movers").get(0)).put("changePct", new java.math.BigDecimal("-9.00"));
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsMoverTheBriefDoesNotList() throws IOException {
        rejects("mover ZZZZ is not in the brief", e -> ((ObjectNode) watchlist().path("movers").get(0)).put("ticker", "ZZZZ"));
    }

    @Test
    void rejectsAsOfAndTrackThatDifferFromBrief() throws IOException {
        watchlist().put("asOf", "Nov 15 close");
        ((ObjectNode) edition.path("learn")).put("track", "Cooking");
        List<String> problems = check(null);
        assertTrue(problems.stream().anyMatch(p -> p.contains("watchlist.asOf")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("learn.track")), problems.toString());
    }

    @Test
    void rejectsEarningsDateThatDiffersFromBrief() throws IOException {
        rejects("earnings entry NVDA 2026-11-19 is not in the brief", e -> ((ObjectNode) watchlist().path("earnings").get(0)).put("date", "2026-11-19"));
    }

    @Test
    void rejectsMacroEventTheBriefDoesNotList() throws IOException {
        rejects("is not in the brief", e -> ((ObjectNode) watchlist().path("macro").get(0)).put("event", "Invented release"));
    }

    @Test
    void rejectsDeepDiveOnTickerThatDidNotJustReport() throws IOException {
        addCandidate("https://example.com/dd", "SAMPLE: Results", "excerpt");
        rejects("does not list as just reported", e -> watchlist().putObject("earningsDeepDive")
                .put("ticker", "NVDA").put("results", "r").put("guidance", "g").put("url", "https://example.com/dd").putArray("quotes"));
    }

    @Test
    void acceptsDeepDiveOnTickerThatJustReported() throws IOException {
        addCandidate("https://example.com/dd", "SAMPLE: Results", "excerpt");
        brief.putArray("reportedYesterday").add("NVDA");
        watchlist().putObject("earningsDeepDive")
                .put("ticker", "NVDA").put("results", "r").put("guidance", "g").put("url", "https://example.com/dd").putArray("quotes");
        assertEquals(List.of(), check(null));
    }

    @Test
    void rejectsTagForTickerOffTheWatchlist() throws IOException {
        rejects("ticker IBM is not on the watchlist", e -> ((ArrayNode) firstStory().path("tags")).add("IBM"));
    }

    @Test
    void rejectsWhenBriefIsMissing() throws IOException {
        check(null);
        Files.delete(root.resolve("brief/" + DATE + ".json"));
        List<String> problems = Validate.check(root, DATE, null);
        assertTrue(problems.stream().anyMatch(p -> p.contains("no brief/")), problems.toString());
    }

    @Test
    void reportsMissingAndMalformedEditionFiles() throws IOException {
        assertTrue(Validate.check(root, DATE, null).get(0).contains("missing edition file"));
        Path file = new Editions(root).file(DATE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{ not json");
        assertTrue(Validate.check(root, DATE, null).get(0).contains("not valid JSON"));
    }

    @Test
    void linkCheckFailsOnGoneAndUnreachableButToleratesBotBlocks() throws IOException {
        Fetcher statuses = new Fetcher() {
            public String get(String url) {
                throw new UnsupportedOperationException();
            }

            public int status(String url) {
                if (url.endsWith("/sample-lead")) return 404;
                if (url.endsWith("/sample-2")) return -1;
                if (url.endsWith("/sample-3")) return 403;
                if (url.endsWith("/sample-4")) return 410;
                return 200;
            }
        };
        List<String> problems = check(statuses);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("dead link (HTTP 404)"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("unreachable") && p.contains("/sample-2")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("HTTP 410")));
    }
}
