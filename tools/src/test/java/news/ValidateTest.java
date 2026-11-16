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
        rejects("words; the limit is", e -> {
            String long600 = "word ".repeat(110).strip(); // under the 600-character field cap
            for (var section : e.path("sections")) {
                for (var item : section.path("items")) {
                    ((ObjectNode) item).put("summary", long600);
                }
            }
            ((ObjectNode) e.path("learn")).put("body", "word ".repeat(790).strip());
            for (var story : e.path("frontPage")) {
                ((ObjectNode) story).put("summary", long600);
            }
            for (var mover : e.path("watchlist").path("movers")) {
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
        rejects("does not list as just reported", e -> watchlist().putObject("earningsDeepDive")
                .put("ticker", "NVDA").put("results", "r").put("guidance", "g").put("url", "https://example.com/dd").putArray("quotes"));
    }

    @Test
    void acceptsDeepDiveOnTickerThatJustReported() throws IOException {
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
