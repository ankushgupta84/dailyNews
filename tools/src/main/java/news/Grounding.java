package news;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks that an edition invents nothing: every link, headline and figure must trace to the brief.
 * The checks are mechanical. They cannot tell whether a summary restates its excerpt correctly, and
 * numbers written as words ("two million") are not seen.
 */
final class Grounding {

    /** A figure as written in digits. Commas are dropped before comparing, so "1,200" equals "1200". */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");

    /** Fields that carry no claim of their own: the link is checked by provenance, the headline by copy. */
    private static final Set<String> UNCHECKED_FIELDS = Set.of("url", "headline", "title");

    private final List<String> problems;
    private final Map<String, String> sourceText = new HashMap<>(); // URL -> text the editor may draw on
    private final Map<String, String> candidateTitle = new HashMap<>(); // URL -> exact headline to copy
    private final Set<String> briefNumbers;
    private final Map<String, String> earningsText = new HashMap<>(); // ticker -> its earningsResults entry

    private Grounding(List<String> problems, JsonNode brief, String briefText) {
        this.problems = problems;
        for (JsonNode c : brief.path("candidates")) {
            String url = c.path("url").asText();
            add(url, c.path("title").asText() + " " + c.path("excerpt").asText());
            candidateTitle.putIfAbsent(url, c.path("title").asText());
        }
        for (JsonNode mover : brief.path("moverNews")) {
            for (JsonNode h : mover.path("headlines")) {
                String publisher = h.path("publisher").asText();
                add(h.path("url").asText(), h.path("title").asText() + (publisher.isEmpty() ? "" : " " + publisher));
            }
        }
        for (JsonNode r : brief.path("earningsResults")) {
            earningsText.put(r.path("ticker").asText(), r.toString());
        }
        briefNumbers = numbers(briefText);
    }

    static void check(List<String> problems, JsonNode edition, JsonNode brief, String briefText) {
        Grounding g = new Grounding(problems, brief, briefText);
        g.walk(edition, "");
        g.checkHeadlines(edition);
        g.checkUnlinkedNotes(edition);
    }

    private void add(String url, String text) {
        sourceText.merge(url, text, (a, b) -> a + " " + b);
    }

    /** A1 for every "url" anywhere, A3 for every object that carries one. */
    private void walk(JsonNode node, String path) {
        if (node.isObject()) {
            if (node.path("url").isTextual()) {
                String url = node.path("url").asText();
                if (!sourceText.containsKey(url)) {
                    problems.add("link not in the brief's candidates or moverNews at " + where(path) + ": " + url);
                } else if (!path.equals("learn")) { // the Learn piece explains its page and may carry its figures
                    checkFigures(node, path, url);
                }
            }
            node.fields().forEachRemaining(e -> walk(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]");
            }
        }
    }

    private void checkFigures(JsonNode object, String path, String url) {
        String source = sourceText.get(url);
        Set<String> allowed = numbers(source);
        if (path.equals("watchlist.earningsDeepDive")) { // EPS against consensus comes from the brief, not the article
            allowed.addAll(numbers(earningsText.getOrDefault(object.path("ticker").asText(), "")));
        }
        object.fields().forEachRemaining(e -> {
            if (UNCHECKED_FIELDS.contains(e.getKey())) {
                return;
            }
            String field = path + "." + e.getKey();
            if (e.getValue().isTextual()) {
                requireNumbers(field, e.getValue().asText(), allowed, "the source text for " + url);
            } else if (e.getValue().isArray()) {
                for (int i = 0; i < e.getValue().size(); i++) {
                    JsonNode item = e.getValue().get(i);
                    if (!item.isTextual()) {
                        continue;
                    }
                    requireNumbers(field + "[" + i + "]", item.asText(), allowed, "the source text for " + url);
                    if (e.getKey().equals("quotes") && !source.contains(item.asText())) {
                        problems.add("quote at " + field + "[" + i + "] is not in the source text for " + url);
                    }
                }
            }
        });
    }

    /** A2: the headline is the source's own, copied exactly. */
    private void checkHeadlines(JsonNode edition) {
        for (int i = 0; i < edition.path("frontPage").size(); i++) {
            checkHeadline(edition.path("frontPage").get(i), "frontPage[" + i + "]", "headline");
        }
        JsonNode sections = edition.path("sections");
        for (int s = 0; s < sections.size(); s++) {
            JsonNode list = sections.get(s).path("items");
            for (int i = 0; i < list.size(); i++) {
                checkHeadline(list.get(i), "sections[" + s + "].items[" + i + "]", "headline");
            }
        }
        JsonNode reads = edition.path("weekend").path("longReads");
        for (int i = 0; i < reads.size(); i++) {
            checkHeadline(reads.get(i), "weekend.longReads[" + i + "]", "headline");
        }
        if (edition.path("weekend").has("listen")) {
            checkHeadline(edition.path("weekend").path("listen"), "weekend.listen", "title");
        }
    }

    private void checkHeadline(JsonNode item, String path, String field) {
        String url = item.path("url").asText();
        String expected = candidateTitle.get(url);
        if (expected == null) {
            if (sourceText.containsKey(url)) { // a mover headline is not a candidate; unknown URLs are reported by the walk
                problems.add(path + " links a mover headline, not a candidate: " + url);
            }
            return;
        }
        if (!item.path(field).asText().strip().equals(expected.strip())) {
            problems.add(path + "." + field + " must be the source's headline, copied exactly: \"" + expected.strip() + "\"");
        }
    }

    /** Figures in text with no link of its own may come from anywhere in the brief. */
    private void checkUnlinkedNotes(JsonNode edition) {
        JsonNode watchlist = edition.path("watchlist");
        for (String list : List.of("earnings", "macro")) {
            for (int i = 0; i < watchlist.path(list).size(); i++) {
                requireNumbers("watchlist." + list + "[" + i + "].note", watchlist.path(list).get(i).path("note").asText(), briefNumbers, "the brief");
            }
        }
        for (int i = 0; i < edition.path("calendar").size(); i++) {
            requireNumbers("calendar[" + i + "].note", edition.path("calendar").get(i).path("note").asText(), briefNumbers, "the brief");
        }
        requireNumbers("weekend.weekInReview", edition.path("weekend").path("weekInReview").asText(), briefNumbers, "the brief");
    }

    private void requireNumbers(String location, String text, Set<String> allowed, String allowedIn) {
        for (String n : new java.util.LinkedHashSet<>(numberList(text))) {
            if (!allowed.contains(n)) {
                problems.add("ungrounded figure " + n + " at " + location + ": not in " + allowedIn);
            }
        }
    }

    private static String where(String path) {
        return path.isEmpty() ? "the edition root" : path;
    }

    private static Set<String> numbers(String text) {
        return new HashSet<>(numberList(text));
    }

    private static List<String> numberList(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            out.add(m.group().replace(",", ""));
        }
        return out;
    }
}
