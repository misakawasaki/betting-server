## SimpleSessionKeyGenerator

#### **Key Decisions**

Generate session keys that are **fully stateless** — both the `customerId` and the expiration timestamp are encoded directly into the key. This eliminates the need for a server-side session map, cleanup thread, and any shared mutable state. Validation is a pure computation: parse the prefix and expiry, compare against the current time.

#### **Key Format**

[hex-digit-length][shifted-digits][expiry-hex(8)][random-padding]

- **`[hex-digit-length]`**: A single hexadecimal character (0–9, a–f) representing the **number of digits** in the original customer ID.
  For example, if the customer ID is `123`, which has 3 digits, this part will be `'3'`.
- **`[shifted-digits]`**: Each digit character of the customer ID is encoded by adding the ASCII value of `'A'` (65) to it. This shifts the digit character into the letter range to avoid having plain digits in this segment.
  For example:
  
  - `'1' + 'A' = 'r'` (ASCII 49 + 65 = 114 → `'r'`)
  - `'2' + 'A' = 's'`
  - `'3' + 'A' = 't'`
    So `123` becomes `'r'`, `'s'`, `'t'` → `"rst"` in this segment.
- **`[expiry-hex]`**: 8 lowercase hex characters encoding the expiration time in seconds since the Unix epoch. This allows stateless expiry checking — if the current time exceeds the encoded expiry, the key is rejected without any map lookup.
- **`[random-padding]`**: The remaining characters are filled with cryptographically secure random characters selected from the set `0-9, a-z, A-Z`, ensuring unpredictability.

> ✅ This design allows both the customer ID and session validity to be verified purely from the key — no session map, no cleanup thread, no locks, no shared mutable state. Every request validates independently at full throughput.

#### **Default Key Length: 24**

The minimum key size is `1 (hex-len) + N (shifted digits) + 8 (expiry hex)` where N is the digit count of the customer ID. The default length of 24 ensures at least 5 random padding characters even for the largest 10-digit customer IDs.

## SortedSet

The `SortedSet<T, U extends Comparable<U>>` is a **compact, bounded, sorted collection** optimized for maintaining a fixed number of top-*N* elements in order. It supports efficient insertion, update, and retrieval operations with a focus on **low memory footprint** and **predictable performance**, particularly in scenarios where only the highest-valued entries are of interest.

### 1. Hybrid Architecture

The implementation combines:

- `T[] keys` and `U[] values`: Store elements in arrays for cache efficiency and compact memory layout.
- `int[] links`: A packed representation of a doubly linked list where each `int` stores both previous and next indices (16 bits each), enabling O(1) pointer updates.
- `Map<T, Integer> indexMap`: Provides O(1) lookup of a key’s current index, crucial for fast updates.

This design avoids object overhead and supports efficient reordering while keeping memory usage low.

### 2. Array-Based Doubly Linked List

Instead of using object-based nodes, the linked list is represented using integer indices and bit-packing in a primitive array. This:

- Reduces garbage collection pressure.
- Improves cache locality.
- Is feasible because the maximum size is limited to 64, so 16-bit indices are sufficient.

The list maintains ascending order, with the smallest valid element at `minIndex`.

### 3. Bounded Size (Max 64)

The size limit ensures:

- Indices fit in 16 bits for bit-packing.
- Search and insertion operations have bounded, predictable performance.
- Memory consumption remains small and constant.

This makes the structure ideal for use cases like leaderboards or top-K tracking.

### 4. Sorted Insertion and Eviction

- Elements are inserted in sorted order using a bidirectional search from a starting point.
- When the set is full and a new element would be inserted at `minIndex`, the smallest element is evicted.
- The `addOrUpdate` method only accepts updates if the new value is greater than the current one, ensuring monotonic progression.

### 5. Efficient Top-N Retrieval

- `getTopN(n)` returns entries in descending order by traversing backward from the tail of the list.
- It returns a lazy `AbstractList` view, avoiding data copying and providing O(1) access per element.

### 6. Performance and Simplicity

Thread safety is intentionally omitted to:

- Avoid synchronization overhead (e.g., locks, atomic operations).
- Keep the implementation simple and fast.
- Reduce memory footprint by not storing thread-safe data structures.

## BettingStoreImpl

`BettingStoreImpl` is a high-performance, thread-safe implementation of a betting store that manages customer bets on different betting offers. It is designed to support concurrent access with low contention, efficient top-N queries, and reliable asynchronous processing.

### 1. Sharded Single-Threaded Executors for Concurrency Control

Instead of using locks or concurrent data structures for all operations, this implementation uses **sharding via dedicated single-threaded executors**.

- There are `BUCKETS = availableProcessors()` executors, one per CPU core.
- Each `BetOfferId` is mapped to an executor using consistent hashing:
  `(id.hashCode() & 0x7FFFFFFF) % BUCKETS`
- All operations on the same bet offer are routed to the same executor.

This ensures:

- **Ordered processing** of bets for the same offer.
- **No locks** needed within a shard — operations are serialized by the executor.
- **High concurrency** across different offers.

This approach reduces contention and avoids the overhead of fine-grained locking.

### 2. Synchronous Processing with CompletableFuture

All operations (`placeBet` and `queryTopBets`) are routed to the offer's shard executor via `CompletableFuture.supplyAsync(...).join()`.

- The caller blocks on `join()` until the shard executor completes the operation.
- Because the HTTP server runs on virtual threads, blocking is nearly free — the parked virtual thread yields its carrier thread to serve other requests.
- `placeBet` returns `boolean` (accepted or rejected), so the HTTP handler can send an accurate response (204 vs. rejection) before the client receives it.
- This provides natural backpressure: if a shard falls behind, requests slow down instead of queuing into an unbounded buffer.

Both write and read operations use the same synchronous pattern, ensuring the client always sees a response that reflects the actual state of the store.

### 3. Thread-Safe Data Store with ConcurrentHashMap

The main data structure is a `ConcurrentHashMap` that maps `BetOfferId` to `SortedSet<CustomerId, Stake>`.

- `computeIfAbsent` is thread-safe and ensures lazy initialization of `SortedSet` instances.
- Since each `SortedSet` is only accessed by one dedicated executor, no additional synchronization is needed on the sets themselves.

This combines global thread safety with local single-threaded access for efficiency.

### 4. SortedSet for Efficient Top-N Queries

The `SortedSet` class is used to maintain customers sorted by stake in descending order.

- It supports fast insertion and update: only higher stakes are accepted.
- `getTopN(n)` returns the highest-stake bets in O(n) time with minimal overhead.
- The structure is bounded and cache-friendly, ideal for ranking use cases.

Because each `SortedSet` is accessed by only one thread (the shard executor), its lack of thread safety is not a problem.

### 5. Hash-Based Sharding vs. Global Locking

Using sharded executors avoids the need for:

- Global locks
- Concurrent sorted data structures
- Complex synchronization on updates

Instead, concurrency is managed at the routing level, simplifying the logic and improving scalability.

### 6. Virtual Threads Make Blocking Free

The HTTP server uses `Executors.newVirtualThreadPerTaskExecutor()`, so each request runs on its own virtual thread. When a handler calls `placeBet` or `queryTopBets`, the virtual thread parks on `join()` and the underlying carrier thread is immediately freed to process other requests.

This means synchronous blocking has no throughput cost — the traditional tradeoff (blocking = lost concurrency) does not apply. The accept rate is bounded by the actual processing rate of the shard executors, not by artificial async decoupling that hides queued work behind a premature 204.
