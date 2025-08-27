## SimpleSessionKeyGenerator

#### **Key Decisions**

Generate cryptographically secure session keys that **encode the `customerId` (prefix)** directly, enabling **stateless lookup** of the associated customer without additional map lookup.

#### **Key Format**

[hex-digit-length][shifted-digits][random-suffix]

- **`[hex-digit-length]`**: A single hexadecimal character (0–9, a–f) representing the **number of digits** in the original customer ID.
  For example, if the customer ID is `123`, which has 3 digits, this part will be `'3'`.
- **`[shifted-digits]`**: Each digit of the customer ID is encoded by adding the ASCII value of `'A'` (65) to it. This shifts the digit character into the letter range to avoid having plain digits in this segment.
  For example:
  
  - `'1' + 'A' = 'B'` (ASCII 49 + 65 = 66 → `'B'`)
  - `'2' + 'A' = 'C'`
  - `'3' + 'A' = 'D'`
    So `123` becomes `'B'`, `'C'`, `'D'` → `"BCD"` in this segment.
- **`[random-suffix]`**: The remaining characters are filled with cryptographically secure random characters selected from the set `0-9, a-z, A-Z`, ensuring unpredictability and security.

> ✅ This design allows the original customer ID to be efficiently and securely reconstructed from the session key without requiring additional storage.

## SessionManager

### 🔍 Why Do We Need the `keys` Deque?

The `Deque<Integer> keys` (implemented as `ArrayDeque`) plays a critical role in **efficient and scalable session cleanup**. Its primary purpose is to **track session identifiers (`customerId`) that may have expired**, so they can be safely removed during periodic cleanup — **without scanning the entire session map**.

Without this queue, the cleanup task would need to iterate over all entries in the `sessions` map to check validity, resulting in `O(n)` time complexity per cleanup cycle — which becomes inefficient as the number of sessions grows.

Instead, this design uses a **lazy eviction strategy**:

- Whenever a new session is created, its `customerId` is enqueued into `keys`.
- The background cleaner thread only checks the **head** of the queue.
- If the session at the head is still valid, it stops — because newer sessions are always added to the tail, and validity time is fixed (10 minutes).
- Only expired sessions at the front are removed.

This ensures that **cleanup cost is proportional to the number of expired sessions**, not the total number of sessions.

### ✅ Why `ArrayDeque` Was Chosen

**FIFO Access Pattern**: Sessions are created over time and expire in roughly the same order (FIFO). `ArrayDeque` efficiently supports insertion at the tail (`offerLast`) and removal from the head (`pollFirst`).

**High Performance**: `ArrayDeque` offers `O(1)` amortized time for `offer`, `poll`, and `peek` operations. It's backed by a dynamic array, making it faster than `LinkedList` for most use cases due to better cache locality.

**No Random Access Needed**: We only need to inspect and remove elements from the ends of the queue — a perfect match for deque semantics.

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

### 2. Asynchronous Processing with CompletableFuture

All write operations (`placeBet`) are executed asynchronously using `CompletableFuture.runAsync`.

- The caller does not block.
- Errors are logged asynchronously via `whenComplete`, avoiding uncaught exceptions in the executor.

Read operations (`queryTopBets`) use `supplyAsync` and `join()` to wait synchronously, ensuring a clean API while still leveraging the per-offer executor for consistency.

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

### 6. Synchronous Query with Asynchronous Underpinning

`queryTopBets` returns immediately after the result is ready, using `join()` to wait on the async task.

- This keeps the API simple and blocking-free for the caller’s thread (in terms of CPU work).
- The actual wait is short and localized to the offer’s shard.

For low-latency queries, this trade-off is acceptable and avoids callback complexity.
