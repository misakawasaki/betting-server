package utils;

import java.util.*;

/**
 * A compact, fixed-size ordered set that maintains elements sorted by their values.
 * It supports efficient insertion, update, and retrieval of top-N elements.
 *
 * <p>This implementation uses a hybrid data structure:
 * <ul>
 *   <li>An array-based circular doubly linked list to maintain order.</li>
 *   <li>Two arrays to store keys and values.</li>
 *   <li>A hash map for O(1) key-to-index lookup.</li>
 * </ul>
 *
 * <p>The set is bounded in size. When full, older or smaller elements are evicted
 * from the head (minimum end) based on the sort order.
 *
 * <p>Elements are sorted in ascending order of their values. The {@code lowerBound}
 * is used as a sentinel value to mark unused or evicted positions.
 *
 * @param <T> the type of keys
 * @param <U> the type of values, must implement {@link Comparable}
 */
public final class SortedSet<T, U extends Comparable<U>> {

    // Default capacity of the set
    private static final int DEFAULT_LENGTH = 20;

    // Maximum capacity of the set
    private final int length;

    // Array to store keys
    private final T[] keys;

    // Array to store values (used for sorting)
    private final U[] values;

    /**
     * Array to store linked list pointers (prev and next) in a packed format.
     * Each integer holds two 16-bit shorts:
     * - High 16 bits: previous index
     * - Low 16 bits: next index
     */
    private final int[] links;

    /**
     * Map from key to its current index in the arrays.
     * Used for O(1) lookup during update operations.
     */
    private final Map<T, Integer> indexMap;

    /**
     * Index of the current minimum (head of the list).
     * Points to the smallest valid element, or the insertion point.
     */
    private int minIndex;

    /**
     * Sentinel value used to mark empty or invalid positions.
     * Must be less than or equal to any valid value.
     */
    private final U lowerBound;

    /**
     * Constructs a new sorted set with default capacity and the given lower bound.
     *
     * @param lowerBound the sentinel value for empty slots
     */
    public SortedSet(U lowerBound) {
        this(DEFAULT_LENGTH, lowerBound);
    }

    /**
     * Constructs a new sorted set with the specified capacity and lower bound.
     *
     * @param length the maximum number of elements (must be 1-64)
     * @param lowerBound the sentinel value for empty slots
     * @throws IllegalArgumentException if length is invalid
     */
    @SuppressWarnings("unchecked")
    public SortedSet(int length, U lowerBound) {
        if (length <= 0 || length > 64) {
            throw new IllegalArgumentException("Invalid length");
        }

        this.length = length;
        this.keys = (T[]) new Object[length];
        this.values = (U[]) new Comparable[length];
        this.links = new int[length];
        // Use power-of-two capacity for HashMap to reduce collisions
        indexMap = new HashMap<>(nextPowerOfTwo(length));
        for (int i = 0; i < length; ++i) {
            this.setValue(i, lowerBound);
            this.setPrev(i, i - 1);
            this.setNext(i, i + 1);
        }
        setNext(length - 1, -1);
        this.minIndex = 0;
        this.lowerBound = lowerBound;
    }

    /**
     * Adds or updates an element with the given key and value.
     *
     * <p>If the key already exists and the new value is not greater than the old one,
     * the operation is rejected. Otherwise, the element is inserted in sorted order.
     *
     * <p>If the insertion happens at {@code minIndex}, the current minimum is evicted.
     *
     * @param key   the key of the element
     * @param value the value of the element
     * @return true if the element was added or updated; false if rejected
     */
    public boolean addOrUpdate(T key, U value) {
        Integer index = indexMap.getOrDefault(key, minIndex);
        if (value.compareTo(getValue(index)) < 0) {
            return false;
        }
        if (index == minIndex) {
            minIndex = getNext(minIndex);
            indexMap.remove(keys[index]);
        }
        insert(index, length - 1, value);
        indexMap.put(key, index);
        keys[index] = key;
        return true;
    }

    /**
     * Returns the top N elements with the largest values (in descending order).
     *
     * <p>The returned list is a live view, but modifications are not supported.
     *
     * @param n the number of top elements to return
     * @return a list of the top N entries, sorted in descending order
     */
    public List<Map.Entry<T, U>> getTopN(int n) {
        int actualLength = length;
        int tail = minIndex;
        while (getNext(tail) != -1) {
            if (getValue(tail) == lowerBound) {
                actualLength--;
            }
            tail = getNext(tail);
        }

        final int first = tail;
        final int cnt = Math.min(actualLength, n);

        return new AbstractList<>() {
            @Override
            public int size() {
                return cnt;
            }

            @Override
            public Map.Entry<T, U> get(int i) {
                if (i < 0 || i >= cnt) throw new IndexOutOfBoundsException();
                int idx = walk(first, i);
                return new AbstractMap.SimpleImmutableEntry<>(
                        keys[idx],
                        values[idx]
                );
            }

            private int walk(int idx, int step) {
                while (step-- > 0) idx = getPrev(idx);
                return idx;
            }
        };
    }

    /**
     * Inserts a value at the given position and updates the linked list
     * to maintain sorted order.
     *
     * @param pos   the index where the key will be placed
     * @param start the starting point for finding insertion position
     * @param value the value to insert
     */
    private void insert(int pos, int start, U value) {
        int index = findPos(start, value);
        if (pos == start) {
            setValue(pos, value);
            return;
        }

        setNext(getPrev(pos), getNext(pos));
        setPrev(getNext(pos), getPrev(pos));
        setValue(pos, value);
        setPrev(pos, index);
        setNext(pos, getNext(index));
        setPrev(getNext(index), pos);
        setNext(index, pos);
    }

    /**
     * Finds the correct insertion position for a value by traversing
     * the linked list from a starting point.
     *
     * @param start the starting index
     * @param value the value to insert
     * @return the index after which the new node should be inserted
     */
    private int findPos(int start, U value) {
        U currentValue = getValue(start);
        if (currentValue.compareTo(value) >= 0) {
            while (
                    getPrev(start) != -1 &&
                            getValue(getPrev(start)).compareTo(value) >= 0
            ) {
                start = getPrev(start);
            }
        } else {
            while (
                    getNext(start) != -1 &&
                            getValue(getNext(start)).compareTo(value) <= 0
            ) {
                start = getNext(start);
            }
        }
        return start;
    }

    public U getValue(int index) {
        return values[index];
    }

    private int getPrev(int index) {
        return (short) ((links[index] >> 16) & 0xFFFF);
    }

    private int getNext(int index) {
        return (short) (links[index] & 0xFFFF);
    }

    private void setValue(int index, U value) {
        values[index] = value;
    }

    private void setPrev(int index, int prev) {
        if (index == -1) {
            return;
        }
        links[index] = (prev << 16) | (links[index] & 0xFFFF);
    }

    private void setNext(int index, int next) {
        if (index == -1) {
            return;
        }
        links[index] = (links[index] & 0xFFFF0000) | next;
    }

    /**
     * Returns the smallest power of two greater than or equal to n.
     * Used to initialize the HashMap with optimal capacity.
     */
    private static int nextPowerOfTwo(int n) {
        n--;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        return n + 1;
    }
}
