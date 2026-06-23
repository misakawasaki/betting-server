package http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Context {
    /**
     * Maximum allowed request body size (1 KB). The only POST body in this
     * server is a stake integer (at most 11 chars), so 1 KB is generous.
     * Requests exceeding this are rejected to prevent OOM from oversized payloads.
     */
    private static final int MAX_BODY_SIZE = 1024;
    private final HttpExchange exchange;
    private final String method;
    private final String path;
    private final String query;
    private final Map<String, String> pathParams = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();
    private boolean aborted = false;
    private int statusCode = 200;
    private boolean committed = false;

    public Context(HttpExchange exchange) {
        this.exchange = exchange;
        this.path = exchange.getRequestURI().getPath();
        this.query = exchange.getRequestURI().getQuery();
        this.method = exchange.getRequestMethod();
        parseQueryParams();
    }

    private void parseQueryParams() {
        if (query != null) {
            for (String param : query.split("&")) {
                String[] parts = param.split("=", 2);
                if (parts.length == 2) {
                    queryParams.put(parts[0], parts[1]);
                }
            }
        }
    }

    public String path() {
        return path;
    }

    public String method() {
        return method;
    }

    public Map<String, String> pathParams() {
        return pathParams;
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    public String queryParam(String name) {
        return queryParams.get(name);
    }

    public String queryParam(String name, String defaultValue) {
        return queryParams.getOrDefault(name, defaultValue);
    }

    public Context status(int code) {
        this.statusCode = code;
        return this;
    }

    public Context text(String text) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/plain");
        byte[] response = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, response.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(response);
        }
        committed = true;
        return this;
    }

    public void commit() throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
        committed = true;
        exchange.close();
    }

    public void abort() {
        this.aborted = true;
    }

    public boolean isAborted() {
        return aborted;
    }

    public boolean isCommitted() {
        return committed;
    }

    public String getRequestBody() throws IOException {
        // Early reject via Content-Length header if present
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Integer.parseInt(contentLength) > MAX_BODY_SIZE) {
                    throw new IOException("Request body exceeds limit of " + MAX_BODY_SIZE + " bytes");
                }
            } catch (NumberFormatException ignored) {
                // Malformed Content-Length — fall through to bounded read
            }
        }

        // Bounded read: read at most MAX_BODY_SIZE + 1 bytes
        // If we get more than MAX_BODY_SIZE, the body is too large (also
        // handles chunked transfer encoding where Content-Length is absent)
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_SIZE + 1);
            if (bytes.length > MAX_BODY_SIZE) {
                throw new IOException("Request body exceeds limit of " + MAX_BODY_SIZE + " bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
