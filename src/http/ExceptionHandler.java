package http;

import java.io.IOException;

@FunctionalInterface
public interface ExceptionHandler {
    void handle(Context ctx, Exception e) throws IOException;
}
