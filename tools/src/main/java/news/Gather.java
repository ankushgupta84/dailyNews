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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * Usage: Gather [--root DIR] [--date YYYY-MM-DD] [--scheduled]
 */
public final class Gather {

    static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    /** A focus ticker whose close or opening move reached at least this many percent is a mover. */
    static final BigDecimal MOVER_PCT = new BigDecimal("3.0");
    /** Earnings are listed this many days ahead, matching the edition calendar's two weeks. */
    static final int EARNINGS_DAYS = 14;
    /** How far back to look for the previous trading session: covers a long weekend plus a holiday. */
    static final int SESSION_LOOKBACK = 5;
    private static final ZoneId NEW_YORK = Market.NEW_YORK;
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 30);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Duration DAILY_LOOKBACK = Duration.ofHours(36);
    private static final Duration WEEKLY_LOOKBACK = Duration.ofDays(8);

    private Gather() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(".");
        LocalDate date = LocalDate.now(ZONE);
        boolean scheduled = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root" -> root = Path.of(args[++i]);
                case "--date" -> date = LocalDate.parse(args[++i]);
                case "--scheduled" -> scheduled = true;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (editionExists(root, date)) {
            System.out.println("Edition " + date + " already exists; leaving brief/" + date + ".json unchanged.");
            return;
        }
        Instant now = Instant.now();
        Path out = root.resolve("brief").resolve(date + ".json");
        String skip = scheduledSkip(scheduled, now, Files.exists(out));
        if (skip != null) {
            System.out.println(skip);
            return;
        }
        ObjectNode brief = build(root, date, now, Fetcher.http());
        Files.createDirectories(out.getParent());
        Files.writeString(out, JSON.writeValueAsString(brief) + "\n");
        System.out.printf("Wrote %s: %d candidates, %d sources reached, %d failed, %d problems%n", out,
                brief.path("candidates").size(), brief.path("sourcesReached").asInt(),
                brief.path("sourcesFailed").size(), brief.path("problems").size());
    }

    /**
     * Validate checks an edition against its brief, so a brief must not change once its edition is written.
     */
    static boolean editionExists(Path root, LocalDate date) {
        return Files.exists(new Editions(root).file(date.toString()));
    }

    /**
     * Why a scheduled run should do nothing, or null if it should build the brief. The workflow fires at
     * two UTC hours so one of them is 9:35 a.m. New York in either season; only the first run at or after
     * the open builds the brief. Without {@code --scheduled} (a manual run) neither rule applies.
     */
    static String scheduledSkip(boolean scheduled, Instant now, boolean briefExists) {
        if (!scheduled) {
            return null;
        }
        if (now.atZone(NEW_YORK).toLocalTime().isBefore(MARKET_OPEN)) {
            return "Scheduled run before the 9:30 a.m. New York open; writing nothing.";
        }
        if (briefExists) {
            return "Scheduled run, but the brief already exists; writing nothing.";
        }
        return null;
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

        addMarket(brief, problems, profile, date, new Market(fetcher, Clock.fixed(now, ZoneOffset.UTC)));
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
        Map<String, Market.OpenQuote> opens = new HashMap<>();
        ArrayNode missing = JSON.createArrayNode();
        for (String ticker : tiers.keySet()) {
            try {
                Market.Reading reading = market.read(ticker, date);
                prices.add(reading.close());
                if (reading.open() != null) {
                    opens.put(ticker, reading.open());
                }
                if (reading.openProblem() != null) {
                    problems.add("Opening quote for " + ticker + " unreadable (" + reading.openProblem()
                            + "). Say nothing about " + ticker + "'s move at the open.");
                }
            } catch (IOException e) {
                missing.add(ticker);
            }
        }

        // The trading day most tickers last closed on. A ticker whose latest close is older is stale.
        Map<LocalDate, Integer> counts = new HashMap<>();
        prices.forEach(p -> counts.merge(p.date(), 1, Integer::sum));
        LocalDate asOf = counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);

        // The label comes from the first opening quote (in profile order) that the page will show.
        String openAsOf = null;
        for (Market.Price p : prices) {
            if (openAsOf == null && p.date().equals(asOf) && opens.containsKey(p.ticker())) {
                openAsOf = opens.get(p.ticker()).timestamp();
            }
        }

        ArrayNode priceNodes = JSON.createArrayNode();
        List<Market.Price> moved = new ArrayList<>();
        prices.sort(Comparator.comparing((Market.Price p) -> p.changePct().abs()).reversed().thenComparing(Market.Price::ticker));
        for (Market.Price p : prices) {
            if (!p.date().equals(asOf)) {
                missing.add(p.ticker());
                continue;
            }
            String tier = tiers.get(p.ticker());
            ObjectNode node = priceNodes.addObject().put("ticker", p.ticker()).put("tier", tier).put("close", p.close()).put("changePct", p.changePct());
            Market.OpenQuote open = opens.get(p.ticker());
            if (open != null) {
                node.put("openPrice", open.price()).put("openPct", open.changePct());
            }
            if (tier.equals("focus") && biggestMove(p, open).compareTo(MOVER_PCT) >= 0) {
                moved.add(p);
            }
        }
        // Largest move first, by the larger of the close and the open; ties by ticker.
        moved.sort(Comparator.comparing((Market.Price p) -> biggestMove(p, opens.get(p.ticker()))).reversed().thenComparing(Market.Price::ticker));
        ArrayNode movers = JSON.createArrayNode();
        ArrayNode moverNews = JSON.createArrayNode();
        for (Market.Price p : moved) {
            ObjectNode mover = movers.addObject().put("ticker", p.ticker()).put("changePct", p.changePct());
            if (opens.containsKey(p.ticker())) {
                mover.put("openPct", opens.get(p.ticker()).changePct());
            }
            addMoverNews(moverNews, problems, market, p.ticker(), asOf);
        }
        if (asOf == null) {
            problems.add("No prices could be fetched. Leave watchlist.movers empty and set asOf to \"unavailable\".");
        }
        brief.put("asOf", asOf == null ? "unavailable" : asOf.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + " close");
        if (asOf != null) {
            brief.put("asOfDate", asOf.toString()); // absent, not null, when prices are unavailable
        }
        if (openAsOf != null) {
            brief.put("openAsOf", openAsOf); // absent, not null, when no opening quote exists
        }
        brief.set("movers", movers);
        brief.set("moverNews", moverNews);
        brief.set("prices", priceNodes);
        brief.set("pricesMissing", missing);

        ArrayNode earnings = JSON.createArrayNode();
        ArrayNode reported = JSON.createArrayNode();
        LocalDate previous = null;
        try {
            previous = previousSession(market, date);
            for (Market.Earning e : previous == null ? List.<Market.Earning>of() : market.earningsOn(previous)) {
                // A report before the open on the previous session was news for that day's edition.
                if ("focus".equals(tiers.get(e.ticker())) && !e.time().equals("before the open")) {
                    reported.add(e.ticker());
                }
            }
            for (LocalDate day = date; !day.isAfter(date.plusDays(EARNINGS_DAYS)); day = day.plusDays(1)) {
                for (Market.Earning e : market.earningsOn(day)) {
                    if ("focus".equals(tiers.get(e.ticker()))) {
                        earnings.addObject().put("ticker", e.ticker()).put("date", e.date().toString()).put("time", e.time());
                        if (day.equals(date) && e.time().equals("before the open")) {
                            reported.add(e.ticker());
                        }
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
        brief.set("earningsResults", earningsResults(problems, market, reported, previous == null ? date : previous));
    }

    /**
     * The latest weekday before {@code date} with any company on Nasdaq's earnings calendar, which skips
     * weekends and market holidays (the calendar is empty on both). It does not depend on {@code asOf},
     * which moves to {@code date} itself when Gather runs after the close. Null if none of the last
     * {@link #SESSION_LOOKBACK} days had reports.
     */
    static LocalDate previousSession(Market market, LocalDate date) throws IOException {
        for (int back = 1; back <= SESSION_LOOKBACK; back++) {
            LocalDate day = date.minusDays(back);
            if (!market.earningsOn(day).isEmpty()) {
                return day;
            }
        }
        return null;
    }

    /**
     * EPS against consensus for each ticker that just reported. A table whose latest row predates
     * {@code since} has not been updated for this report yet and is left out rather than shown as current.
     */
    private static ArrayNode earningsResults(ArrayNode problems, Market market, ArrayNode reported, LocalDate since) {
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode t : reported) {
            String ticker = t.asText();
            try {
                Market.EarningsResult r = market.latestEarnings(ticker);
                if (r.dateReported().isBefore(since)) {
                    problems.add("Earnings results for " + ticker + " not posted yet (latest is " + r.dateReported()
                            + "). Give no EPS figures for " + ticker + ".");
                    continue;
                }
                out.addObject().put("ticker", ticker).put("fiscalQuarter", r.fiscalQuarter()).put("dateReported", r.dateReported().toString())
                        .put("eps", r.eps()).put("consensus", r.consensus()).put("surprisePct", r.surprisePct());
            } catch (IOException e) {
                problems.add("Earnings results for " + ticker + " unavailable (" + e.getMessage() + "). Give no EPS figures for " + ticker + ".");
            }
        }
        return out;
    }

    /** The larger absolute move, in percent, of the close and the opening quote (if any). */
    private static BigDecimal biggestMove(Market.Price p, Market.OpenQuote open) {
        BigDecimal close = p.changePct().abs();
        return open == null ? close : close.max(open.changePct().abs());
    }

    /**
     * Headlines for one mover published on or after the session date, so they are about that session
     * or later. A lookup that fails costs that ticker its headlines, not the brief.
     */
    private static void addMoverNews(ArrayNode moverNews, ArrayNode problems, Market market, String ticker, LocalDate session) {
        ObjectNode entry = moverNews.addObject().put("ticker", ticker);
        ArrayNode headlines = entry.putArray("headlines");
        try {
            for (Market.Headline h : market.headlines(ticker, session)) {
                ObjectNode node = headlines.addObject().put("title", h.title()).put("url", h.url()).put("published", h.published().toString())
                        .put("primary", h.primary());
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

    /** Clock skew allowed between a feed's timestamps and ours; anything later is a bad date. */
    private static final Duration FUTURE_SLACK = Duration.ofHours(1);

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
                Instant until = now.plus(FUTURE_SLACK);
                pending.add(pool.submit(() -> {
                    IOException failure = null;
                    for (int attempt = 0; attempt < 2; attempt++) { // feeds hiccup; one retry, then report
                        try {
                            String xml = fetcher.get(source.path("feed").asText());
                            return new Result(source, Feeds.parse(xml, since, until, source.path("max").asInt(3)), null);
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
