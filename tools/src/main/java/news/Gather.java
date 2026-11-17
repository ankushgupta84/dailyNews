package news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Builds brief/DATE.json: everything the editor model needs for one edition.
 * Numbers and state are computed here so the model never has to produce them.
 *
 * Usage: Gather [--root DIR] [--date YYYY-MM-DD]
 */
public final class Gather {

    static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    /** A focus ticker whose close moved at least this many percent is a mover. */
    static final BigDecimal MOVER_PCT = new BigDecimal("3.0");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    /** How far back mover headlines may reach. */
    static final int MOVER_NEWS_DAYS = 3;
    private static final Duration DAILY_LOOKBACK = Duration.ofHours(36);
    private static final Duration WEEKLY_LOOKBACK = Duration.ofDays(8);

    private Gather() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(".");
        LocalDate date = LocalDate.now(ZONE);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--date" -> date = LocalDate.parse(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        ObjectNode brief = build(root, date, Instant.now(), Fetcher.http());
        Path out = root.resolve("brief").resolve(date + ".json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writeValueAsString(brief) + "\n");
        System.out.printf("Wrote %s: %d candidates, %d sources reached, %d failed, %d problems%n", out,
                brief.path("candidates").size(), brief.path("sourcesReached").asInt(),
                brief.path("sourcesFailed").size(), brief.path("problems").size());
    }

    /** Pure of clock and network: both are passed in, so tests are deterministic. */
    static ObjectNode build(Path root, LocalDate date, Instant now, Fetcher fetcher) throws IOException {
        JsonNode profile = JSON.readTree(Files.readString(root.resolve("profile.json")));
        JsonNode sources = JSON.readTree(Files.readString(root.resolve("sources.json")));
        Editions editions = new Editions(root);
        String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase(Locale.US);

        ObjectNode brief = JSON.createObjectNode();
        ArrayNode problems = JSON.createArrayNode();
        brief.put("date", date.toString());
        brief.put("kind", Editions.kind(date));
        JsonNode track = profile.path("learnRotation").path(weekday);
        if (!track.isTextual()) {
            throw new IOException("profile.json learnRotation has no entry for " + weekday);
        }
        brief.put("learnTrack", track.asText());

        addMarket(brief, problems, profile, date, new Market(fetcher));
        addMacro(brief, root, date);
        addCandidates(brief, sources, weekday, now, fetcher);

        ArrayNode recent = brief.putArray("recentHeadlines");
        for (Editions.Headline h : editions.recentHeadlines(date, 3)) {
            recent.addObject().put("date", h.date()).put("headline", h.headline()).put("url", h.url());
        }
        ArrayNode titles = brief.putArray("learnTitlesUsed");
        editions.learnTitles().forEach(titles::add);
        brief.set("problems", problems);

        if (brief.path("sourcesReached").asInt() == 0) {
            throw new IOException("no source could be reached; refusing to write an empty brief");
        }
        return brief;
    }

    // ---------- market data ----------

    private static void addMarket(ObjectNode brief, ArrayNode problems, JsonNode profile, LocalDate date, Market market) {
        Map<String, String> tiers = new LinkedHashMap<>();
        profile.path("tickers").path("focus").forEach(t -> tiers.put(t.asText(), "focus"));
        profile.path("tickers").path("general").forEach(t -> tiers.put(t.asText(), "general"));

        List<Market.Price> prices = new ArrayList<>();
        ArrayNode missing = JSON.createArrayNode();
        for (String ticker : tiers.keySet()) {
            try {
                prices.add(market.price(ticker, date));
            } catch (IOException e) {
                missing.add(ticker);
            }
        }

        // The trading day most tickers last closed on. A ticker whose latest close is older is stale.
        Map<LocalDate, Integer> counts = new HashMap<>();
        prices.forEach(p -> counts.merge(p.date(), 1, Integer::sum));
        LocalDate asOf = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        ArrayNode priceNodes = JSON.createArrayNode();
        ArrayNode movers = JSON.createArrayNode();
        ArrayNode moverNews = JSON.createArrayNode();
        prices.sort(Comparator.comparing((Market.Price p) -> p.changePct().abs()).reversed().thenComparing(Market.Price::ticker));
        for (Market.Price p : prices) {
            if (!p.date().equals(asOf)) {
                missing.add(p.ticker());
                continue;
            }
            String tier = tiers.get(p.ticker());
            priceNodes.addObject().put("ticker", p.ticker()).put("tier", tier).put("close", p.close()).put("changePct", p.changePct());
            if (tier.equals("focus") && p.changePct().abs().compareTo(MOVER_PCT) >= 0) {
                movers.addObject().put("ticker", p.ticker()).put("changePct", p.changePct());
                addMoverNews(moverNews, problems, market, p.ticker(), date);
            }
        }
        if (asOf == null) {
            problems.add("No prices could be fetched. Leave watchlist.movers empty and set asOf to \"unavailable\".");
        }
        brief.put("asOf", asOf == null ? "unavailable" : asOf.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + " close");
        brief.set("movers", movers);
        brief.set("moverNews", moverNews);
        brief.set("prices", priceNodes);
        brief.set("pricesMissing", missing);

        ArrayNode earnings = JSON.createArrayNode();
        ArrayNode reported = JSON.createArrayNode();
        try {
            if (asOf != null) {
                for (Market.Earning e : market.earningsOn(asOf)) {
                    if ("focus".equals(tiers.get(e.ticker()))) {
                        reported.add(e.ticker());
                    }
                }
            }
            for (LocalDate day = date; !day.isAfter(date.plusDays(7)); day = day.plusDays(1)) {
                for (Market.Earning e : market.earningsOn(day)) {
                    if ("focus".equals(tiers.get(e.ticker()))) {
                        earnings.addObject().put("ticker", e.ticker()).put("date", e.date().toString()).put("time", e.time());
                    }
                }
            }
        } catch (IOException e) {
            // A partial calendar would read as "nothing else reports this week", so drop it whole.
            earnings.removeAll();
            reported.removeAll();
            problems.add("Earnings calendar unavailable (" + e.getMessage() + "). Leave watchlist.earnings empty.");
        }
        brief.set("earnings", earnings);
        brief.set("reportedYesterday", reported);
    }

    /** Recent headlines for one mover. A lookup that fails costs that ticker its headlines, not the brief. */
    private static void addMoverNews(ArrayNode moverNews, ArrayNode problems, Market market, String ticker, LocalDate date) {
        ObjectNode entry = moverNews.addObject().put("ticker", ticker);
        ArrayNode headlines = entry.putArray("headlines");
        try {
            for (Market.Headline h : market.headlines(ticker, date.minusDays(MOVER_NEWS_DAYS))) {
                ObjectNode node = headlines.addObject().put("title", h.title()).put("url", h.url()).put("published", h.published().toString());
                if (!h.publisher().isEmpty()) {
                    node.put("publisher", h.publisher());
                }
            }
        } catch (IOException e) {
            problems.add("Headlines for mover " + ticker + " unavailable (" + e.getMessage()
                    + "). Do not guess a cause for " + ticker + "; it has no headline to link.");
        }
    }

    private static void addMacro(ObjectNode brief, Path root, LocalDate date) throws IOException {
        ArrayNode macro = brief.putArray("macro");
        JsonNode events = JSON.readTree(Files.readString(root.resolve("macro.json"))).path("events");
        if (!events.isArray()) {
            throw new IOException("macro.json has no \"events\" array");
        }
        String from = date.toString();
        String to = date.plusDays(14).toString();
        for (JsonNode event : events) {
            String day = event.path("date").asText();
            if (day.compareTo(from) >= 0 && day.compareTo(to) <= 0) {
                macro.addObject().put("date", day).put("event", event.path("event").asText());
            }
        }
    }

    // ---------- news candidates ----------

    /** The source's own "lookbackDays" if it has one, else 36 hours for daily sources and 8 days for weekday ones. */
    private static Duration lookback(JsonNode source, String schedule) throws IOException {
        JsonNode days = source.path("lookbackDays");
        if (days.isMissingNode()) {
            return schedule.equals("daily") ? DAILY_LOOKBACK : WEEKLY_LOOKBACK;
        }
        if (!days.isIntegralNumber() || days.asInt() <= 0) {
            throw new IOException("sources.json: \"" + source.path("name").asText()
                    + "\" has lookbackDays " + days + "; it must be a positive integer");
        }
        return Duration.ofDays(days.asInt());
    }

    private record Result(JsonNode source, List<Feeds.Item> items, String failure) {}

    private static void addCandidates(ObjectNode brief, JsonNode sources, String weekday, Instant now, Fetcher fetcher)
            throws IOException {
        List<Future<Result>> pending = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (JsonNode source : sources.path("sources")) {
                String schedule = source.path("schedule").asText();
                // Checked before the schedule test so a bad entry fails today, not on its weekday.
                Duration lookback = lookback(source, schedule);
                if (!schedule.equals("daily") && !schedule.equals(weekday)) {
                    continue;
                }
                Instant since = now.minus(lookback);
                pending.add(pool.submit(() -> {
                    IOException failure = null;
                    for (int attempt = 0; attempt < 2; attempt++) { // feeds hiccup; one retry, then report
                        try {
                            String xml = fetcher.get(source.path("feed").asText());
                            return new Result(source, Feeds.parse(xml, since, source.path("max").asInt(3)), null);
                        } catch (IOException e) {
                            failure = e;
                        }
                    }
                    return new Result(source, List.of(), String.valueOf(failure.getMessage()));
                }));
            }
            ArrayNode candidates = brief.putArray("candidates");
            ArrayNode failed = JSON.createArrayNode();
            Set<String> seen = new HashSet<>();
            int reached = 0;
            for (Future<Result> future : pending) {
                Result r = future.get();
                String name = r.source().path("name").asText();
                if (r.failure() != null) {
                    failed.addObject().put("source", name).put("reason", r.failure());
                    continue;
                }
                reached++;
                for (Feeds.Item item : r.items()) {
                    // Several feeds carry the same story (a wire piece, two NYT sections). The first
                    // source in sources.json wins, so the editor never sees one link twice.
                    if (!seen.add(item.url())) {
                        continue;
                    }
                    candidates.addObject()
                            .put("beat", r.source().path("beat").asText())
                            .put("source", name)
                            .put("title", item.title())
                            .put("url", item.url())
                            .put("published", item.published().toString())
                            .put("excerpt", item.excerpt());
                }
            }
            brief.put("sourcesReached", reached);
            brief.set("sourcesFailed", failed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading feeds", e);
        } catch (ExecutionException e) {
            throw new IOException("feed task failed unexpectedly", e.getCause());
        }
    }
}
