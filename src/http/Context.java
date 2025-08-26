package http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class Context {
    private final HttpExchange exchange;
    private final String method;
    private final String path;
    private final String query;
    private final Map<String, String> pathParams = new HashMap<>();
    private final Map<String, String> queryParams = new HashMap<>();
    private Session session;
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

    public Session session(int customerId, boolean created) {
        if (session != null) {
            return session;
        }
        session = SessionManager.getInstance().getSession(customerId, created);
        return session;
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
        byte[] response = text.getBytes();
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
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return body;
    }
}
