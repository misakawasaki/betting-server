package http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public final class MiniHttpServer {
    private final HttpServer server;
    private final Map<String, List<Route>> routes = new HashMap<>();
    private final List<Middleware> middlewares = new ArrayList<>();
    private final Deque<String> prefixStack = new ArrayDeque<>();
    private ExceptionHandler exceptionHandler = new DefaultExceptionHandler();

    private MiniHttpServer(int port) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.prefixStack.push("");
    }

    public static MiniHttpServer create(int port) {
        try {
            return new MiniHttpServer(port);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create server", e);
        }
    }

    public void exceptionHandler(ExceptionHandler handler) {
        this.exceptionHandler = handler;
    }

    public void group(String prefix, Runnable config) {
        String resolved = resolvePattern(prefixStack.peek(), prefix);
        prefixStack.push(resolved);
        try {
            config.run();
        } finally {
            prefixStack.pop();
        }
    }

    public void before(String pattern, Middleware middleware) {
        String resolved = resolvePattern(prefixStack.peek(), pattern);
        middlewares.add(new PatternMiddleware(resolved, middleware));
    }

    public void get(String pattern, Handler handler) {
        addRoute("GET", pattern, handler);
    }

    public void post(String pattern, Handler handler) {
        addRoute("POST", pattern, handler);
    }

    public void put(String pattern, Handler handler) {
        addRoute("PUT", pattern, handler);
    }

    public void delete(String pattern, Handler handler) {
        addRoute("DELETE", pattern, handler);
    }

    public void start() {
        this.server.createContext("/", this::dispatch);
        this.server.start();
    }

    public void stop() {
        this.server.stop(0);
    }

    private void addRoute(String method, String pattern, Handler handler) {
        String resolved = resolvePattern(prefixStack.peek(), pattern);
        routes.computeIfAbsent(method, _ -> new ArrayList<>()).add(new Route(resolved, handler));
    }

    private static String resolvePattern(String basePrefix, String pattern) {
        String base = basePrefix == null ? "" : basePrefix;
        if (base.endsWith("/*")) {
            base = base.substring(0, base.length() - 2);
        }
        String child = pattern == null ? "" : pattern;
        if (child.isEmpty()) {
            return base.isEmpty() ? "/" : base;
        }
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        if (child.startsWith("/")) {
            child = child.substring(1);
        }
        String joined = (base + child).replaceAll("//+", "/");
        if (!joined.startsWith("/")) {
            joined = "/" + joined;
        }
        return joined;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        // Create context
        Context ctx = new Context(exchange);

        try {
            // Run middlewares
            for (Middleware middleware : middlewares) {
                if (middleware.shouldApply(ctx.path())) {
                    // Extract path parameters for middleware that matches this path
                    if (middleware instanceof PatternMiddleware) {
                        PatternMiddleware patternMiddleware = (PatternMiddleware) middleware;
                        extractPathParams(patternMiddleware.pattern(), ctx.path(), ctx);
                    }
                    middleware.handle(ctx);
                    if (ctx.isAborted()) {
                        if (!ctx.isCommitted()) {
                            ctx.commit();
                        }
                        return;
                    }
                }
            }

            // Find matching route
            Route matchedRoute = findMatchingRoute(ctx.method(), ctx.path());
            if (matchedRoute != null) {
                // Extract path parameters
                extractPathParams(matchedRoute.pattern(), ctx.path(), ctx);
                matchedRoute.handler().handle(ctx);
                if (!ctx.isCommitted()) {
                    ctx.commit();
                }
            } else {
                ctx.status(404).text("Not Found");
            }
        } catch (Exception e) {
            handleException(ctx, e);
        }
    }

    private void handleException(Context ctx, Exception e) throws IOException {
        if (!ctx.isCommitted()) {
            exceptionHandler.handle(ctx, e);
        }
    }

    private Route findMatchingRoute(String method, String path) {
        List<Route> methodRoutes = routes.get(method);
        if (methodRoutes == null) {
            return null;
        }

        for (Route route : methodRoutes) {
            if (matchesPattern(route.pattern(), path)) {
                return route;
            }
        }
        return null;
    }

    private boolean matchesPattern(String pattern, String path) {
        String regexPattern = pattern
                .replaceAll("/:([^/]+)", "/[^/]+")
                .replaceAll("\\*", ".*");
        return Pattern.matches(regexPattern, path);
    }

    private void extractPathParams(String pattern, String path, Context ctx) {
        String[] patternParts = pattern.split("/");
        String[] pathParts = path.split("/");

        for (int i = 0; i < Math.min(patternParts.length, pathParts.length); i++) {
            if (patternParts[i].startsWith(":")) {
                String paramName = patternParts[i].substring(1);
                ctx.pathParams().put(paramName, pathParts[i]);
            }
        }
    }

    private static class DefaultExceptionHandler implements ExceptionHandler {
        @Override
        public void handle(Context ctx, Exception e) throws IOException {
            // Send appropriate error response
            switch (e) {
                case IllegalArgumentException _ ->
                        ctx.status(400).text("Bad Request: " + e.getMessage());
                case SecurityException _ -> ctx.status(403).text("Forbidden: " + e.getMessage());
                case null, default -> ctx.status(500).text("Internal Server Error");
            }
        }
    }
}
