package domain;

import domain.model.Bet;
import domain.model.BetOfferId;
import domain.model.CustomerId;
import domain.model.Stake;
import utils.SortedSet;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A high-performance, thread-safe implementation of {@link BettingStore}
 * for managing bets on betting offers.
 *
 * <p>This implementation uses:
 * <ul>
 *   <li>Sharding via hash-based routing to multiple single-threaded executors
 *       to reduce contention and ensure ordered processing per bet offer.</li>
 *   <li>{@link SortedSet} to maintain customers by stake in descending order
 *       for efficient top-N queries.</li>
 *   <li>Asynchronous processing with {@link CompletableFuture} to avoid
 *       blocking the caller thread.</li>
 * </ul>
 *
 * <p>Each bet offer is mapped to a dedicated single-threaded executor,
 * ensuring that all operations on the same offer are serialized,
 * while allowing high concurrency across different offers.
 */
public final class BettingStoreImpl implements BettingStore,AutoCloseable {

    // Logger for logging errors and operational events
    private static final Logger LOG = Logger.getLogger(BettingStoreImpl.class.getName());

    /**
     * Number of executor shards (one per CPU core by default).
     * Used to distribute load and reduce lock contention.
     */
    private static final int BUCKETS =
            Runtime.getRuntime().availableProcessors();

    /**
     * Array of single-threaded executors, one for each shard.
     * Each executor serializes operations for a subset of bet offers.
     */
    private final ExecutorService[] executors = new ExecutorService[BUCKETS];

    /**
     * Main data store: maps each bet offer ID to a sorted set of customer bets.
     * The sorted set keeps entries ordered by stake (descending) for fast top-N queries.
     *
     * <p>Uses {@link ConcurrentHashMap} for thread-safe access during initialization.
     */
    private final Map<BetOfferId, SortedSet<CustomerId, Stake>> store =
            new ConcurrentHashMap<>();

    /**
     * Constructs a new betting store with sharded executors.
     * One single-threaded executor is created per bucket (CPU core).
     */
    public BettingStoreImpl() {
        for (int i = 0; i < BUCKETS; i++) {
            int finalI = i;
            executors[i] = Executors.newSingleThreadExecutor(r ->
                    new Thread(r, "BettingStore-" + finalI)
            );
        }
    }

    /**
     * Asynchronously places a bet.
     *
     * <p>The operation is routed to a dedicated executor based on the bet offer ID,
     * ensuring that all bets on the same offer are processed in order.
     *
     * <p>If the bet offer does not exist, a new {@link SortedSet} is created with
     * {@link Stake#ZERO} as the lower bound (sentinel value).
     *
     * <p>If the customer has already placed a bet with a higher or equal stake,
     * the new bet is rejected (only higher stakes are accepted).
     *
     * @param bet the bet to place
     * @throws NullPointerException if bet is null
     */
    @Override
    public void placeBet(Bet bet) {
        CompletableFuture.runAsync(
                () -> {
                    SortedSet<CustomerId, Stake> set = store.computeIfAbsent(
                            bet.betOfferId(),
                            k -> new SortedSet<>(Stake.ZERO)
                    );
                    set.addOrUpdate(bet.customerId(), bet.stake());
                },
                executor(bet.betOfferId())
        ).whenComplete((v, ex) -> { if (ex != null) {
            LOG.log(Level.SEVERE, "placed bet failed", ex);
        }});
    }

    /**
     * Synchronously queries the top N bets (by stake) for a given bet offer.
     *
     * <p>The result is returned in descending order of stake (highest first).
     *
     * <p>If the bet offer does not exist or has no bets, an empty list is returned.
     *
     * <p>Note: This method blocks until the query completes. It uses asynchronous
     * execution internally but calls {@link CompletableFuture#join()} to wait.
     *
     * @param offerId the ID of the bet offer
     * @param n       the maximum number of top bets to return
     * @return a list of the top N bets, sorted by stake in descending order
     * @throws IllegalArgumentException if n is negative
     */
    @Override
    @SuppressWarnings("unchecked")
    public List<Bet> queryTopBets(BetOfferId offerId, int n) {
        return (List<Bet>) CompletableFuture.supplyAsync(
                () -> {
                    SortedSet<CustomerId, Stake> set = store.get(offerId);
                    if (set == null) return Collections.emptyList();
                    return set
                            .getTopN(n)
                            .stream()
                            .map(e -> new Bet(offerId, e.getKey(), e.getValue()))
                            .collect(Collectors.toList());
                },
                executor(offerId)
        ).join();
    }

    /**
     * Routes a bet offer ID to the appropriate executor shard.
     *
     * <p>Uses consistent hashing: hash code is masked to ensure non-negative,
     * then modulo BUCKETS determines the shard.
     *
     * @param id the bet offer ID
     * @return the executor responsible for handling operations on this ID
     */
    private ExecutorService executor(BetOfferId id) {
        return executors[(id.hashCode() & 0x7FFFFFFF) % BUCKETS];
    }

    /**
     * Gracefully shuts down all executor services.
     *
     * <p>Should be called when the application is shutting down to prevent
     * resource leaks and ensure all pending tasks are completed.
     *
     * <p>Implements {@link AutoCloseable} for use in try-with-resources.
     */
    @Override
    public void close() {
        for (ExecutorService e : executors) e.shutdown();
    }
}
