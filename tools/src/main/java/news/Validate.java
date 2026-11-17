package news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gate between the editor model and the public page. An edition that fails any check is not published.
 *
 * Usage: Validate [--root DIR] [--check-urls] [DATE]     (DATE defaults to the newest in editions/index.json)
 */
public final class Validate {

    static final int MAX_WORDS = 3200;

    /** Second-person ownership phrases. The paper must never say or imply what the reader owns. */
    private static final Pattern OWNERSHIP = Pattern.compile(
            "\\b(your|my|our)\\s+(portfolio|holdings?|positions?|shares|stocks?|stake|investments?)\\b|\\byou\\s+(own|hold|bought|sold)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final ObjectMapper JSON = new ObjectMapper();

    private Validate() {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(".");
        boolean checkUrls = false;
        String date = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--check-urls" -> checkUrls = true;
                default -> date = args[i];
            }
        }
        if (date == null) {
            List<String> dates = new Editions(root).dates();
            if (dates.isEmpty()) {
                System.out.println("No editions to validate.");
                return;
            }
            date = dates.get(0);
        }
        List<String> problems = check(root, date, checkUrls ? Fetcher.http() : null);
        if (problems.isEmpty()) {
            System.out.println("Edition " + date + " is valid.");
            return;
        }
        System.err.println("Edition " + date + " is NOT valid:");
        problems.forEach(p -> System.err.println("  - " + p));
        System.exit(1);
    }

    /** All problems with the edition for {@code date}; empty means publishable. A null fetcher skips link checks. */
    static List<String> check(Path root, String date, Fetcher fetcher) throws IOException {
        List<String> problems = new ArrayList<>();
        Editions editions = new Editions(root);
        Path file = editions.file(date);
        if (!Files.isRegularFile(file)) {
            return List.of("missing edition file " + root.relativize(file));
        }
        JsonNode edition;
        try {
            edition = JSON.readTree(Files.readString(file));
        } catch (IOException e) {
            return List.of("edition is not valid JSON: " + e.getMessage());
        }

        JsonNode schema = JSON.readTree(Files.readString(root.resolve("schema/edition.schema.json")));
        Set<ValidationMessage> schemaErrors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema).validate(edition);
        schemaErrors.forEach(m -> problems.add("schema: " + m.getMessage()));
        if (!problems.isEmpty()) {
            return problems; // later checks assume the shape is right
        }

        checkArchive(problems, editions, edition, date);
        checkText(problems, edition);
        checkLinks(problems, edition, fetcher);
        checkSourceSpelling(problems, root, edition);
        checkAgainstBrief(problems, root, edition, date);
        return problems;
    }

    private static void checkArchive(List<String> problems, Editions editions, JsonNode edition, String date) throws IOException {
        if (!edition.path("date").asText().equals(date)) {
            problems.add("date field " + edition.path("date").asText() + " does not match file name " + date);
        }
        LocalDate day;
        try {
            day = LocalDate.parse(date);
        } catch (RuntimeException e) {
            problems.add("not a real calendar date: " + date);
            return;
        }
        if (!edition.path("kind").asText().equals(Editions.kind(day))) {
            problems.add("kind is " + edition.path("kind").asText() + " but " + date + " is a " + Editions.kind(day));
        }
        if (edition.has("weekend") && Editions.kind(day).equals("weekday")) {
            problems.add("weekend block on a weekday edition");
        }
        List<String> dates = editions.dates();
        if (!dates.contains(date)) {
            problems.add("editions/index.json does not list " + date);
        }
        if (new HashSet<>(dates).size() != dates.size()) {
            problems.add("editions/index.json lists a date twice");
        }
        if (!dates.equals(dates.stream().sorted(java.util.Comparator.reverseOrder()).toList())) {
            problems.add("editions/index.json is not sorted newest first");
        }
        for (String listed : dates) {
            if (!Files.isRegularFile(editions.file(listed))) {
                problems.add("editions/index.json lists " + listed + " but its file is missing");
            }
        }
    }

    private static void checkText(List<String> problems, JsonNode edition) {
        List<String> strings = new ArrayList<>();
        collectStrings(edition, strings);
        int words = 0;
        for (String s : strings) {
            if (s.startsWith("https://")) {
                continue;
            }
            words += s.isBlank() ? 0 : s.strip().split("\\s+").length;
            Matcher m = OWNERSHIP.matcher(s);
            if (m.find()) {
                problems.add("ownership language \"" + m.group() + "\" in: " + abbreviate(s));
            }
        }
        if (words > MAX_WORDS) {
            problems.add("edition has " + words + " words; the limit is " + MAX_WORDS);
        }
    }

    /**
     * The reader adds a "Read via library" link by exact source name, so "the wall street journal" for
     * "The Wall Street Journal" would silently lose it. Only a match that differs in case or spacing is
     * reported: a name that matches nothing in sources.json is allowed, since the item may not come from a feed.
     */
    private static void checkSourceSpelling(List<String> problems, Path root, JsonNode edition) throws IOException {
        Set<String> names = new HashSet<>();
        JsonNode sources = JSON.readTree(Files.readString(root.resolve("sources.json"))).path("sources");
        sources.forEach(s -> names.add(s.path("name").asText()));
        Map<String, String> byForm = new HashMap<>();
        names.forEach(n -> byForm.put(normalize(n), n));

        List<JsonNode> items = new ArrayList<>();
        edition.path("frontPage").forEach(items::add);
        edition.path("sections").forEach(section -> section.path("items").forEach(items::add));
        for (JsonNode item : items) {
            String source = item.path("source").asText();
            String canonical = byForm.get(normalize(source));
            if (canonical != null && !names.contains(source)) {
                problems.add("source \"" + source + "\" should be spelled \"" + canonical + "\" as in sources.json");
            }
        }
    }

    private static String normalize(String name) {
        return name.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void collectStrings(JsonNode node, List<String> out) {
        if (node.isTextual()) {
            out.add(node.asText());
        } else {
            node.forEach(child -> collectStrings(child, out));
        }
    }

    private static void checkLinks(List<String> problems, JsonNode edition, Fetcher fetcher) {
        Set<String> stories = new HashSet<>();
        List<JsonNode> items = new ArrayList<>();
        edition.path("frontPage").forEach(items::add);
        edition.path("sections").forEach(section -> section.path("items").forEach(items::add));
        for (JsonNode item : items) {
            if (!stories.add(item.path("url").asText())) {
                problems.add("the same story appears twice: " + item.path("url").asText());
            }
        }
        if (fetcher == null) {
            return;
        }
        Set<String> urls = new LinkedHashSet<>();
        collectUrls(edition, urls);
        for (String url : urls) {
            int status = fetcher.status(url);
            // Many news sites answer 401/403/429 to programs yet work in a browser, so only a
            // definite "gone" or an unreachable host counts as a dead link.
            if (status == 404 || status == 410 || status == -1) {
                problems.add("dead link (" + (status == -1 ? "unreachable" : "HTTP " + status) + "): " + url);
            } else if (status != 200) {
                System.out.println("  note: HTTP " + status + " for " + url);
            }
        }
    }

    private static void collectUrls(JsonNode node, Set<String> out) {
        if (node.isObject() && node.path("url").isTextual()) {
            out.add(node.path("url").asText());
        }
        node.forEach(child -> collectUrls(child, out));
    }

    /** Every number and date the editor was told to copy must match what the gather program computed. */
    private static void checkAgainstBrief(List<String> problems, Path root, JsonNode edition, String date) throws IOException {
        Path briefFile = root.resolve("brief").resolve(date + ".json");
        if (!Files.isRegularFile(briefFile)) {
            problems.add("no brief/" + date + ".json to check numbers against");
            return;
        }
        JsonNode brief = JSON.readTree(Files.readString(briefFile));
        JsonNode profile = JSON.readTree(Files.readString(root.resolve("profile.json")));
        Set<String> known = new HashSet<>();
        profile.path("tickers").path("focus").forEach(t -> known.add(t.asText()));
        profile.path("tickers").path("general").forEach(t -> known.add(t.asText()));

        JsonNode watchlist = edition.path("watchlist");
        if (!watchlist.path("asOf").asText().equals(brief.path("asOf").asText())) {
            problems.add("watchlist.asOf \"" + watchlist.path("asOf").asText() + "\" differs from the brief's \"" + brief.path("asOf").asText() + "\"");
        }
        if (!edition.path("learn").path("track").asText().equals(brief.path("learnTrack").asText())) {
            problems.add("learn.track \"" + edition.path("learn").path("track").asText() + "\" differs from the brief's \"" + brief.path("learnTrack").asText() + "\"");
        }
        for (JsonNode mover : watchlist.path("movers")) {
            String ticker = mover.path("ticker").asText();
            JsonNode source = find(brief.path("movers"), "ticker", ticker);
            if (source == null) {
                problems.add("mover " + ticker + " is not in the brief's movers");
            } else if (new BigDecimal(mover.path("changePct").asText()).compareTo(new BigDecimal(source.path("changePct").asText())) != 0) {
                problems.add("mover " + ticker + " changePct " + mover.path("changePct").asText() + " differs from the brief's " + source.path("changePct").asText());
            }
        }
        for (JsonNode earning : watchlist.path("earnings")) {
            JsonNode source = find(brief.path("earnings"), "ticker", earning.path("ticker").asText());
            if (source == null || !source.path("date").asText().equals(earning.path("date").asText())) {
                problems.add("earnings entry " + earning.path("ticker").asText() + " " + earning.path("date").asText() + " is not in the brief");
            }
        }
        for (JsonNode event : watchlist.path("macro")) {
            boolean found = false;
            for (JsonNode source : brief.path("macro")) {
                found |= source.path("date").asText().equals(event.path("date").asText())
                        && source.path("event").asText().equals(event.path("event").asText());
            }
            if (!found) {
                problems.add("macro event \"" + event.path("event").asText() + "\" on " + event.path("date").asText() + " is not in the brief");
            }
        }
        if (watchlist.has("earningsDeepDive")) {
            String ticker = watchlist.path("earningsDeepDive").path("ticker").asText();
            boolean reported = false;
            for (JsonNode t : brief.path("reportedYesterday")) {
                reported |= t.asText().equals(ticker);
            }
            if (!reported) {
                problems.add("earnings deep dive on " + ticker + ", which the brief does not list as just reported");
            }
        }
        Set<String> mentioned = new LinkedHashSet<>();
        watchlist.path("analystAndInsider").forEach(a -> mentioned.add(a.path("ticker").asText()));
        edition.path("frontPage").forEach(s -> s.path("tags").forEach(t -> mentioned.add(t.asText())));
        edition.path("sections").forEach(sec -> sec.path("items").forEach(s -> s.path("tags").forEach(t -> mentioned.add(t.asText()))));
        for (String ticker : mentioned) {
            if (!known.contains(ticker)) {
                problems.add("ticker " + ticker + " is not on the watchlist in profile.json");
            }
        }
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (node.path(field).asText().equals(value)) {
                return node;
            }
        }
        return null;
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
