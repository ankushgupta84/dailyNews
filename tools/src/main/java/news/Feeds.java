package news;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Parses RSS 2.0 and Atom feeds into plain items. */
public final class Feeds {

    public record Item(String title, String url, Instant published, String excerpt) {}

    static final int EXCERPT_CHARS = 240;

    private Feeds() {}

    /**
     * Items from the feed that are dated at or after {@code since} and link to an https page,
     * newest first, at most {@code max}. Undated items are dropped: without a date they would
     * reappear as "new" every day.
     */
    public static List<Item> parse(String xml, Instant since, int max) throws IOException {
        Document doc = parseXml(xml);
        List<Item> items = new ArrayList<>();
        collect(doc.getElementsByTagName("item"), items);   // RSS
        collect(doc.getElementsByTagName("entry"), items);  // Atom
        if (doc.getElementsByTagName("item").getLength() + doc.getElementsByTagName("entry").getLength() == 0) {
            throw new IOException("not a feed: no <item> or <entry> elements");
        }
        return items.stream()
                .filter(i -> i.published() != null && !i.published().isBefore(since))
                .filter(i -> i.url().startsWith("https://") && !i.title().isBlank())
                .sorted(Comparator.comparing(Item::published).reversed())
                .limit(max)
                .toList();
    }

    private static void collect(NodeList nodes, List<Item> out) {
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            String body = firstNonBlank(childText(e, "description"), childText(e, "summary"), childText(e, "content"));
            String title = clean(childText(e, "title"), 300);
            out.add(new Item(
                    title,
                    link(e),
                    date(firstNonBlank(childText(e, "pubDate"), childText(e, "published"), childText(e, "updated"), childText(e, "dc:date"))),
                    excerpt(clean(body, EXCERPT_CHARS), title)));
        }
    }

    /**
     * Some feeds fill the description with boilerplate: Hacker News emits a "Comments" link, and some
     * blogs repeat the title. Either would pass for content, so store nothing instead.
     */
    private static String excerpt(String cleaned, String title) {
        return cleaned.equalsIgnoreCase("Comments") || cleaned.equals(title) ? "" : cleaned;
    }

    private static Document parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Feeds are untrusted input: no external entities or DTD fetching. A DOCTYPE itself is
            // tolerated because some real feeds carry one; the JDK's secure-processing limits apply.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // The JDK caps a document at 100,000 characters by default; busy feeds are larger.
            factory.setAttribute("jdk.xml.maxGeneralEntitySizeLimit", "5000000");
            factory.setAttribute("jdk.xml.totalEntitySizeLimit", "5000000");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null); // errors surface as SAXException, not stderr noise
            return builder.parse(new InputSource(new StringReader(xml.strip())));
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("malformed feed: " + e.getMessage(), e);
        }
    }

    /** Text of the first direct child with this tag name, or "". */
    private static String childText(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child && child.getTagName().equals(tag)) {
                return child.getTextContent();
            }
        }
        return "";
    }

    /** RSS keeps the URL as element text; Atom keeps it in href, preferring rel="alternate". */
    private static String link(Element item) {
        String fallback = "";
        for (Node n = item.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element child && child.getTagName().equals("link")) {
                String href = child.getAttribute("href").strip();
                if (href.isEmpty()) {
                    return child.getTextContent().strip();
                }
                String rel = child.getAttribute("rel");
                if (rel.isEmpty() || rel.equals("alternate")) {
                    return href;
                }
                fallback = href;
            }
        }
        return fallback;
    }

    static Instant date(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            // not RFC 1123: try ISO 8601 next
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // not ISO 8601: try the zoneless US style next
        }
        try {
            // "Nov 16, 2026 12:13pm", used by US trade press; read as US Eastern time.
            return LocalDateTime.parse(t, US_STYLE).atZone(ZoneId.of("America/New_York")).toInstant();
        } catch (DateTimeParseException ignored) {
            return null; // unparseable date: the item counts as undated and is dropped by parse()
        }
    }

    private static final DateTimeFormatter US_STYLE = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM d, yyyy h:mma").toFormatter(Locale.US);

    /** Strips markup and collapses whitespace, then truncates on a word boundary. */
    static String clean(String html, int maxChars) {
        String text = html.replaceAll("(?s)<[^>]*>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#8217;", "’")
                .replaceAll("\\s+", " ").strip();
        if (text.length() <= maxChars) {
            return text;
        }
        int cut = text.lastIndexOf(' ', maxChars);
        return text.substring(0, cut > 0 ? cut : maxChars) + "…";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
