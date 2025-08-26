package http;

import java.time.Duration;

public final class Session {
    private static final long EXPIRATION_MILLIS = Duration.ofMinutes(10).toMillis();;
    private final String sessionKey;
    private final long creationTime;

    public Session(String sessionKey) {
        this.sessionKey = sessionKey;
        this.creationTime = System.currentTimeMillis();
    }

    public String sessionKey() {
        return sessionKey;
    }

    public boolean isValid() {
        return (System.currentTimeMillis() - creationTime) <= EXPIRATION_MILLIS;
    }

    @Override
    public String toString() {
        return "Session{sessionKey='%s', creationTime=%d}".formatted(sessionKey, creationTime);
    }
}
