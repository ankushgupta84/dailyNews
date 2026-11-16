package news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Reads the archive of published editions: where files live and what they already covered. */
public final class Editions {

    public record Headline(String date, String headline, String url) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path root;

    public Editions(Path root) {
        this.root = root;
    }

    public static String kind(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> "saturday";
            case SUNDAY -> "sunday";
            default -> "weekday";
        };
    }

    public Path file(String date) {
        return root.resolve("editions").resolve(date.substring(0, 4)).resolve(date.substring(5, 7)).resolve(date + ".json");
    }

    /** Dates in editions/index.json, as listed (newest first). */
    public List<String> dates() throws IOException {
        JsonNode dates = JSON.readTree(Files.readString(root.resolve("editions/index.json"))).path("dates");
        if (!dates.isArray()) {
            throw new IOException("editions/index.json has no \"dates\" array");
        }
        List<String> out = new ArrayList<>();
        dates.forEach(d -> out.add(d.asText()));
        return out;
    }

    /** Headlines of the {@code count} most recent editions published before {@code date}. */
    public List<Headline> recentHeadlines(LocalDate date, int count) throws IOException {
        List<Headline> out = new ArrayList<>();
        List<String> earlier = dates().stream().filter(d -> d.compareTo(date.toString()) < 0).sorted().toList();
        for (String d : earlier.subList(Math.max(0, earlier.size() - count), earlier.size())) {
            JsonNode edition = JSON.readTree(Files.readString(file(d)));
            for (JsonNode story : edition.path("frontPage")) {
                out.add(new Headline(d, story.path("headline").asText(), story.path("url").asText()));
            }
            for (JsonNode section : edition.path("sections")) {
                for (JsonNode story : section.path("items")) {
                    out.add(new Headline(d, story.path("headline").asText(), story.path("url").asText()));
                }
            }
        }
        return out;
    }

    /** Every learning title published so far. */
    public List<String> learnTitles() throws IOException {
        List<String> out = new ArrayList<>();
        for (String d : dates()) {
            out.add(JSON.readTree(Files.readString(file(d))).path("learn").path("title").asText());
        }
        return out;
    }
}
