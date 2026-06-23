package utils;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * A stateless session key generator that embeds both a numeric prefix (customer ID)
 * and an expiration timestamp directly into the key, enabling fully stateless
 * validation without any server-side session store.
 *
 * <p>The generated key has the following format:
 * <pre>
 * [hex-len][shifted-digits][expiry-hex(8)][random-padding]
 * </pre>
 *
 * Where:
 * <ul>
 *   <li>{@code hex-len} — a single hex character (0–f) representing the digit count
 *       of the prefix. For prefix {@code 123} (3 digits) this is {@code '3'}.</li>
 *   <li>{@code shifted-digits} — each digit character of the prefix shifted by
 *       {@code 'A'} (65) to move it out of the plain-digit range.
 *       For prefix {@code 123}: {@code '1'+65='r'}, {@code '2'+65='s'},
 *       {@code '3'+65='t'} → {@code "rst"}.</li>
 *   <li>{@code expiry-hex} — 8 lowercase hex characters encoding the expiration
 *       time in seconds since the Unix epoch. This allows stateless expiry
 *       checking without a session map.</li>
 *   <li>{@code random-padding} — remaining characters filled with cryptographically
 *       secure random alphanumerics for unpredictability.</li>
 * </ul>
 *
 * <p>Example: prefix=123, expiry at timestamp 1789977600 →
 * {@code "3rst6a9c4e00xYzAb1"} (length 24)
 * <ul>
 *   <li>{@code '3'} → prefix has 3 digits</li>
 *   <li>{@code "rst"} → {@code '1'+'A'}, {@code '2'+'A'}, {@code '3'+'A'}</li>
 *   <li>{@code "6a9c4e00"} → expiry 1789977600 in hex</li>
 *   <li>{@code "xYzAb1"} → random padding</li>
 * </ul>
 *
 * <p>Validation is purely computational: parse the prefix and expiry from the key,
 * then compare the expiry against the current time. No map lookup, no locks,
 * no cleanup thread.
 */
public final class SimpleSessionKeyGenerator {

    /**
     * Character set used for the random suffix of the session key.
     * Includes digits, lowercase and uppercase letters (62 total).
     */
    private static final char[] CANDIDATE_CHARS =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    /**
     * Default length of the generated session key (including all encoded segments).
     * Must be large enough to hold the hex-length (1) + shifted digits (up to 10
     * for a 32-bit int) + expiry hex (8), plus enough random padding for
     * unpredictability.
     */
    private static final int DEFAULT_LENGTH = 24;

    /**
     * Fixed width of the expiry hex segment (8 hex chars = 32 bits = seconds
     * until year 2106).
     */
    private static final int EXPIRY_HEX_WIDTH = 8;

    /**
     * Session validity duration.
     */
    private static final long EXPIRATION_MILLIS = Duration.ofMinutes(10).toMillis();

    /**
     * Cryptographically strong random number generator for generating random characters.
     */
    private static final SecureRandom RAND = new SecureRandom();

    // Private constructor to prevent instantiation (utility class)
    private SimpleSessionKeyGenerator() {}

    /**
     * Generates a session key with the given numeric prefix and the default length.
     *
     * @param prefix the numeric identifier to embed (e.g., customer ID)
     * @return a session key string containing the encoded prefix, expiry, and random padding
     */
    public static String generateSessionKey(int prefix) {
        return generateSessionKey(prefix, DEFAULT_LENGTH);
    }

    /**
     * Generates a session key with the given numeric prefix and specified length.
     *
     * @param prefix the numeric identifier to embed
     * @param length the total length of the generated key (must be sufficient
     *              to hold hex-length + shifted prefix + expiry hex)
     * @return a session key string
     * @throws IllegalArgumentException if length is too small to encode the prefix and expiry
     */
    public static String generateSessionKey(int prefix, int length) {
        String prefixStr = String.valueOf(prefix);
        int prefixLen    = prefixStr.length();

        int minRequired = 1 + prefixLen + EXPIRY_HEX_WIDTH;
        if (length < minRequired) {
            throw new IllegalArgumentException(
                    "Length " + length + " too small; need at least " + minRequired);
        }

        char[] buf = new char[length];
        int pos = 0;

        // Step 1: Encode the length of the prefix as a single hex digit (0-9a-f)
        buf[pos++] = Integer.toHexString(prefixLen).charAt(0);

        // Step 2: Encode each digit of the prefix by shifting it up by 'A'
        for (int i = 0; i < prefixLen; i++) {
            buf[pos++] = (char) (prefixStr.charAt(i) + 'A');
        }

        // Step 3: Encode the expiration timestamp as 8 hex chars (seconds since epoch)
        long expirySeconds = (System.currentTimeMillis() + EXPIRATION_MILLIS) / 1000;
        String expiryHex = String.format("%0" + EXPIRY_HEX_WIDTH + "x", expirySeconds);
        for (int i = 0; i < EXPIRY_HEX_WIDTH; i++) {
            buf[pos++] = expiryHex.charAt(i);
        }

        // Step 4: Fill the rest with random characters from CANDIDATE_CHARS
        while (pos < length) {
            buf[pos++] = CANDIDATE_CHARS[RAND.nextInt(CANDIDATE_CHARS.length)];
        }

        return new String(buf);
    }

    /**
     * Parses and extracts the original numeric prefix from a session key generated by this class.
     *
     * @param sessionKey the session key string (must be non-null and valid format)
     * @return the original numeric prefix
     * @throws IllegalArgumentException if the key is invalid or corrupted
     * @throws NumberFormatException if the decoded prefix string is not a valid integer
     */
    public static int parsePrefix(String sessionKey) {
        if (sessionKey == null || sessionKey.isEmpty()) {
            throw new IllegalArgumentException("session key is null or empty");
        }
        int len = Character.digit(sessionKey.charAt(0), 16);
        if (len < 1 || sessionKey.length() < 1 + len) {
            throw new IllegalArgumentException("invalid session key: bad prefix length");
        }
        char[] digitChars = new char[len];
        for (int i = 0; i < len; i++) {
            digitChars[i] = (char) (sessionKey.charAt(1 + i) - 'A');
        }
        return Integer.parseInt(new String(digitChars));
    }

    /**
     * Parses the expiration timestamp (in milliseconds since epoch) from a session key.
     *
     * @param sessionKey the session key string
     * @return the expiration time in milliseconds since the Unix epoch
     * @throws IllegalArgumentException if the key is invalid or corrupted
     */
    public static long parseExpiry(String sessionKey) {
        if (sessionKey == null || sessionKey.isEmpty()) {
            throw new IllegalArgumentException("session key is null or empty");
        }
        int prefixLen = Character.digit(sessionKey.charAt(0), 16);
        if (prefixLen < 1) {
            throw new IllegalArgumentException("invalid session key: bad prefix length");
        }
        int expiryStart = 1 + prefixLen;
        if (sessionKey.length() < expiryStart + EXPIRY_HEX_WIDTH) {
            throw new IllegalArgumentException("session key too short for expiry segment");
        }
        String expiryHex = sessionKey.substring(expiryStart, expiryStart + EXPIRY_HEX_WIDTH);
        return Long.parseLong(expiryHex, 16) * 1000;
    }

    /**
     * Checks whether a session key is valid (well-formed and not expired).
     *
     * <p>This is a pure computational check — no map lookup, no locks, no I/O.
     * Safe to call at full request throughput.
     *
     * @param sessionKey the session key to validate, or {@code null}
     * @return {@code true} if the key is well-formed and has not expired
     */
    public static boolean isValid(String sessionKey) {
        if (sessionKey == null || sessionKey.isEmpty()) {
            return false;
        }
        int prefixLen = Character.digit(sessionKey.charAt(0), 16);
        if (prefixLen < 1) {
            return false;
        }
        int expiryStart = 1 + prefixLen;
        if (sessionKey.length() < expiryStart + EXPIRY_HEX_WIDTH) {
            return false;
        }
        try {
            long expiryMillis = Long.parseLong(
                    sessionKey.substring(expiryStart, expiryStart + EXPIRY_HEX_WIDTH), 16) * 1000;
            return System.currentTimeMillis() < expiryMillis;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
