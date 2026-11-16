package news;

import java.io.IOException;

/** A Fetcher backed by a function, so tests never touch the network. */
final class FakeFetcher implements Fetcher {

    interface Responder {
        String respond(String url) throws IOException;
    }

    private final Responder responder;

    FakeFetcher(Responder responder) {
        this.responder = responder;
    }

    @Override
    public String get(String url) throws IOException {
        return responder.respond(url);
    }

    @Override
    public int status(String url) {
        throw new UnsupportedOperationException();
    }
}
