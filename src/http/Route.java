package http;

import java.util.regex.Pattern;

public record Route(String pattern, Pattern compiledPattern, Handler handler) {
    public Route(String pattern, Handler handler) {
        this(pattern, compile(pattern), handler);
    }

    private static Pattern compile(String pattern) {
        String regex = pattern
                .replaceAll("/:([^/]+)", "/[^/]+")
                .replaceAll("\\*", ".*");
        return Pattern.compile(regex);
    }
}
