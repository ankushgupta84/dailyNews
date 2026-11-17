package news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class FeedsTest {

    private static final Instant SINCE = Instant.parse("2026-11-15T00:00:00Z");

    private static String rss(String items) {
        return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>t</title>" + items + "</channel></rss>";
    }

    private static String item(String title, String link, String pubDate) {
        return "<item><title>" + title + "</title><link>" + link + "</link>"
                + (pubDate == null ? "" : "<pubDate>" + pubDate + "</pubDate>")
                + "<description><![CDATA[<p>Hello <b>world</b> &amp; more</p>]]></description></item>";
    }

    @Test
    void parsesRssNewestFirstAndStripsMarkup() throws IOException {
        List<Feeds.Item> items = Feeds.parse(rss(
                item("Older", "https://a.example/1", "Sun, 15 Nov 2026 08:00:00 GMT")
                        + item("Newer", "https://a.example/2", "Mon, 16 Nov 2026 18:31:30 +0000")), SINCE, 10);
        assertEquals(List.of("Newer", "Older"), items.stream().map(Feeds.Item::title).toList());
        assertEquals("Hello world & more", items.get(0).excerpt());
        assertEquals(Instant.parse("2026-11-16T18:31:30Z"), items.get(0).published());
    }

    @Test
    void dropsOldUndatedBadlyDatedAndNonHttpsItems() throws IOException {
        List<Feeds.Item> items = Feeds.parse(rss(
                item("Old", "https://a.example/old", "Sat, 14 Nov 2026 23:59:59 GMT")
                        + item("Undated", "https://a.example/undated", null)
                        + item("Garbage date", "https://a.example/garbage", "yesterday-ish")
                        + item("Plain http", "http://a.example/http", "Mon, 16 Nov 2026 10:00:00 GMT")
                        + item("Script", "javascript:alert(1)", "Mon, 16 Nov 2026 10:00:00 GMT")
                        + item("Boundary", "https://a.example/edge", "Sun, 15 Nov 2026 00:00:00 GMT")), SINCE, 10);
        assertEquals(List.of("Boundary"), items.stream().map(Feeds.Item::title).toList());
    }

    @Test
    void storesNoExcerptWhenItIsCommentsOrJustTheTitle() throws IOException {
        String comments = "<item><title>HN post</title><link>https://a.example/1</link><pubDate>Mon, 16 Nov 2026 10:00:00 GMT</pubDate>"
                + "<description><![CDATA[<a href=\"https://news.ycombinator.com/item?id=1\">comments</a>]]></description></item>";
        String echo = "<item><title>Same words</title><link>https://a.example/2</link><pubDate>Mon, 16 Nov 2026 09:00:00 GMT</pubDate>"
                + "<description>Same words</description></item>";
        String real = "<item><title>Real</title><link>https://a.example/3</link><pubDate>Mon, 16 Nov 2026 08:00:00 GMT</pubDate>"
                + "<description>Comments on the new design</description></item>";
        List<Feeds.Item> items = Feeds.parse(rss(comments + echo + real), SINCE, 10);
        assertEquals(List.of("", "", "Comments on the new design"), items.stream().map(Feeds.Item::excerpt).toList());
    }

    @Test
    void respectsMax() throws IOException {
        StringBuilder many = new StringBuilder();
        for (int i = 10; i < 40; i++) {
            many.append(item("T" + i, "https://a.example/" + i, "Mon, 16 Nov 2026 10:" + i + ":00 GMT"));
        }
        List<Feeds.Item> items = Feeds.parse(rss(many.toString()), SINCE, 4);
        assertEquals(List.of("T39", "T38", "T37", "T36"), items.stream().map(Feeds.Item::title).toList());
    }

    @Test
    void parsesAtomPreferringTheAlternateLink() throws IOException {
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>Post</title>"
                + "<link rel=\"self\" href=\"https://a.example/self\"/><link rel=\"alternate\" href=\"https://a.example/post\"/>"
                + "<updated>2026-11-16T09:00:00Z</updated><published>2026-11-16T08:00:00-08:00</published>"
                + "<summary>Short</summary></entry></feed>";
        Feeds.Item item = Feeds.parse(atom, SINCE, 5).get(0);
        assertEquals("https://a.example/post", item.url());
        assertEquals(Instant.parse("2026-11-16T16:00:00Z"), item.published());
        assertEquals("Short", item.excerpt());
    }

    @Test
    void readsZonelessUsDatesAsEastern() {
        assertEquals(Instant.parse("2026-11-16T17:13:00Z"), Feeds.date("Nov 16, 2026 12:13pm"));
        assertNull(Feeds.date(""));
        assertNull(Feeds.date("13/45/2026"));
    }

    @Test
    void truncatesExcerptOnAWordBoundary() {
        String cleaned = Feeds.clean("<p>" + "alpha ".repeat(100) + "</p>", 240);
        assertTrue(cleaned.length() <= 241, "length " + cleaned.length());
        assertTrue(cleaned.endsWith("alpha…"));
    }

    @Test
    void rejectsMalformedXmlAndNonFeeds() {
        assertThrows(IOException.class, () -> Feeds.parse("<rss><channel><item>", SINCE, 5));
        assertThrows(IOException.class, () -> Feeds.parse("<!DOCTYPE html><html><body>Blocked</body></html>", SINCE, 5));
        assertThrows(IOException.class, () -> Feeds.parse("", SINCE, 5));
    }

    @Test
    void doesNotResolveExternalEntities() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>"
                + "<rss><channel><item><title>&xxe;</title><link>https://a.example/x</link>"
                + "<pubDate>Mon, 16 Nov 2026 10:00:00 GMT</pubDate></item></channel></rss>";
        try {
            List<Feeds.Item> items = Feeds.parse(evil, SINCE, 5);
            // If the parser accepts the document, the entity must have expanded to nothing,
            // which leaves a blank title and so no item.
            assertEquals(List.of(), items);
        } catch (IOException expected) {
            // Refusing the document outright is equally safe.
        }
    }

    @Test
    void acceptsFeedsLargerThanTheJdkDefaultEntityLimit() throws IOException {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append(item("Title " + i + " " + "x".repeat(400), "https://a.example/" + i, "Mon, 16 Nov 2026 10:00:00 GMT"));
        }
        assertTrue(big.length() > 150_000);
        assertEquals(400, Feeds.parse(rss(big.toString()), SINCE, 1000).size());
    }

    @Test
    void fetcherDecodesGzipAndDropsByteOrderMark() throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(zipped)) {
            gz.write("﻿<rss/>".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals("<rss/>", Fetcher.Http.text(zipped.toByteArray()));
        assertEquals("plain", Fetcher.Http.text("plain".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Fetcher.Http.text("﻿x".getBytes(StandardCharsets.UTF_8)).startsWith("﻿"));
        assertEquals("", Fetcher.Http.text(new byte[0]));
    }
}
