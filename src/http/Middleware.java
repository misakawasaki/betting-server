package http;

import java.io.IOException;

@FunctionalInterface
public interface Middleware {
    void handle(Context ctx) throws IOException;

    default boolean shouldApply(String path) {
        return true;
    }
}
