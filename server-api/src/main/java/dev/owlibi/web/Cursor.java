package dev.owlibi.web;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * A position in a newest-first list, so the next page continues after the last item already shown.
 *
 * <p>The cursor is canonical and versioned. It confers no membership or snapshot authority.
 */
public record Cursor(Instant time, UUID id) {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAXIMUM_LIMIT = 100;

    /** The URL-safe text form sent to the browser. */
    public String encode() {
        String plain = "1|" + time + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.US_ASCII));
    }

    /** Reads a cursor sent back by the browser, or returns {@code null} for the first page. */
    public static Cursor decode(String input) {
        if (input == null) {
            return null;
        }
        try {
            if (input.length() > 512 || !input.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            String plain = new String(Base64.getUrlDecoder().decode(input), StandardCharsets.US_ASCII);
            String[] parts = plain.split("\\|", -1);
            if (parts.length != 3 || !parts[0].equals("1")) {
                throw new IllegalArgumentException();
            }
            Cursor cursor = new Cursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
            if (!Timestamp.from(cursor.time()).toInstant().equals(cursor.time())) {
                throw new IllegalArgumentException();
            }
            if (!cursor.encode().equals(input)) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (RuntimeException failure) {
            throw ApiFailure.malformed();
        }
    }

    /** Reads a page size from 1 to 100, defaulting to 20. */
    public static int limit(String input) {
        if (input == null) {
            return DEFAULT_LIMIT;
        }
        try {
            if (!input.matches("[1-9][0-9]{0,2}")) {
                throw new IllegalArgumentException();
            }
            int value = Integer.parseInt(input);
            if (value > MAXIMUM_LIMIT) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException failure) {
            throw ApiFailure.malformed();
        }
    }
}
