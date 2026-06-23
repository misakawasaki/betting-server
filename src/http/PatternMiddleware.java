package http;

import java.io.IOException;
import java.util.regex.Pattern;

public class PatternMiddleware implements Middleware {
    private final String pattern;
    private final Pattern compiledPattern;
    private final Middleware delegate;

    public PatternMiddleware(String pattern, Middleware delegate) {
        this.pattern = pattern;
        this.compiledPattern = compile(pattern);
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
        return compiledPattern.matcher(path).matches();
    }

    private static Pattern compile(String pattern) {
        String regex = pattern
                .replaceAll("/:([^/]+)", "/[^/]+")
                .replaceAll("\\*", ".*");
        return Pattern.compile(regex);
    }
}
