package news;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Reads a URL as text. An interface so tests can supply saved files instead of live sites. */
public interface Fetcher {

    /** Returns the body of a 200 response; throws IOException for anything else. */
    String get(String url) throws IOException;

    /** Status of a URL, for link checking: an HTTP status code, or -1 if the host cannot be reached. */
    int status(String url);

    static Fetcher http() {
        return new Http();
    }

    final class Http implements Fetcher {
        // Several feeds and the Nasdaq API refuse requests without a browser-like agent.
        private static final String AGENT =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
        private static final Duration TIMEOUT = Duration.ofSeconds(20);

        private final HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIMEOUT)
                .build();

        /** Decodes a body as UTF-8 text. Some servers send gzip unasked, and some prepend a byte-order mark. */
        static String text(byte[] body) throws IOException {
            if (body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b) {
                try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            return text.startsWith("﻿") ? text.substring(1) : text;
        }

        private HttpResponse<byte[]> send(String url) throws IOException {
            HttpRequest request;
            try {
                request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("User-Agent", AGENT)
                        .header("Accept", "application/json, application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.5")
                        .GET().build();
            } catch (IllegalArgumentException e) {
                throw new IOException("bad URL: " + url, e);
            }
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted: " + url, e);
            }
        }

        @Override
        public String get(String url) throws IOException {
            HttpResponse<byte[]> response = send(url);
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return text(response.body());
        }

        @Override
        public int status(String url) {
            try {
                return send(url).statusCode();
            } catch (IOException e) {
                return -1;
            }
        }
    }
}
