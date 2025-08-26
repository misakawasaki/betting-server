package http;

import java.io.IOException;
import java.util.regex.Pattern;

public class PatternMiddleware implements Middleware {
    private final String pattern;
    private final Middleware delegate;

    public PatternMiddleware(String pattern, Middleware delegate) {
        this.pattern = pattern;
        this.delegate = delegate;
    }

    public String pattern() {
        return pattern;
    }

    @Override
    public void handle(Context ctx) throws IOException {
        delegate.handle(ctx);
    }

    @Override
    public boolean shouldApply(String path) {
        String regexPattern = pattern
                .replaceAll("/:([^/]+)", "/[^/]+")
                .replaceAll("\\*", ".*");
        return Pattern.matches(regexPattern, path);
    }
}
