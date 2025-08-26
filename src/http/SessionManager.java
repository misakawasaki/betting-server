package http;

import utils.SimpleSessionKeyGenerator;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

/**
 * A thread-safe singleton session manager that manages user sessions with automatic cleanup.
 * Sessions are stored in a concurrent map and cleaned periodically based on validity.
 * The cleanup task runs on a dedicated daemon thread.
 */
public enum SessionManager implements AutoCloseable{
    INSTANCE;

    // Clean up interval: 1 minute
    private static final long CLEAN_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    /**
     * Thread-safe map to store active sessions.
     * Key: session identifier (Integer)
     * Value: Session object
     */
    private final Map<Integer, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Deque to maintain the order of session keys for cleanup.
     * Only invalid sessions at the head will be removed during cleanup.
     */
    private final Deque<Integer> keys = new ArrayDeque<>();

    /**
     * Flag to ensure the cleaner is started only once.
     */
    private final AtomicBoolean cleanerStarted = new AtomicBoolean(false);

    /**
     * Executor service for running the periodic cleanup task.
     * Marked as volatile to ensure visibility across threads.
     */
    private volatile ScheduledExecutorService cleaner;

    /**
     * Retrieves a session by key.
     *
     * @param key    the session identifier
     * @param create whether to create a new session if not found or invalid
     * @return the existing valid session, a newly created session, or null if not created
     */
    public Session getSession(Integer key, boolean create) {
        return sessions.compute(key, (k, old) -> {
            if (old != null && old.isValid()) {
                return old;
            }
            if (!create) {
                return null;
            }
            cleaner.submit(() -> keys.offerLast(key));
            return new Session(SimpleSessionKeyGenerator.generateSessionKey(key));
        });
    }

    /**
     * Gets the singleton instance and ensures the cleaner is started.
     *
     * @return the singleton instance
     */
    public static SessionManager getInstance() {
        return INSTANCE.withCleanerStarted();
    }

    /**
     * Lazily starts the cleanup task if not already started.
     *
     * @return this instance
     */
    private SessionManager withCleanerStarted() {
        if (cleanerStarted.compareAndSet(false, true)) {
            cleaner = newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-cleaner");
                t.setDaemon(true);
                return t;
            });
            cleaner.scheduleWithFixedDelay(
                    () -> {
                        while (!keys.isEmpty()) {
                            Integer key = keys.peekFirst();
                            Session s   = sessions.get(key);
                            if (s == null || s.isValid()) {
                                break;
                            }
                            sessions.remove(key);
                            keys.pollFirst();
                        }
                    },
                    CLEAN_INTERVAL_MILLIS,
                    CLEAN_INTERVAL_MILLIS,
                    TimeUnit.MILLISECONDS
            );
        }
        return this;
    }

    /**
     * Shuts down the cleaner executor gracefully.
     * Should be called during application shutdown.
     */
    @Override
    public void close() {
        if (cleaner != null) {
            cleaner.shutdown();
        }
    }
}
