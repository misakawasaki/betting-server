package http;

import java.io.IOException;

@FunctionalInterface
public interface Handler {
    void handle(Context ctx) throws IOException;
}
