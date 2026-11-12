
package dev.owlibi.reference.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CursorTest {

    @Test
    void encodesAndDecodesCursor() {
        Cursor original = new Cursor(
            Instant.parse("2026-11-12T10:00:00Z"),
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
        );

        assertEquals(original, Cursor.decode(original.encode()));
    }

    @Test
    void returnsNullWhenDecodingNull() {
        assertNull(Cursor.decode(null));
    }

    @Test
    void rejectsMalformedCursor() {
        assertThrows(ApiFailure.class, () -> Cursor.decode("invalid!"));
    }

    @Test
    void rejectsUnsupportedCursorVersion() {
        String encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "2|2026-11-12T10:00:00Z|123e4567-e89b-12d3-a456-426614174000"
                    .getBytes(StandardCharsets.US_ASCII)
            );

        assertThrows(ApiFailure.class, () -> Cursor.decode(encoded));
    }

    @Test
    void rejectsCursorWithInvalidUuid() {
        String encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                "1|2026-11-12T10:00:00Z|invalid-uuid"
                    .getBytes(StandardCharsets.US_ASCII)
            );

        assertThrows(ApiFailure.class, () -> Cursor.decode(encoded));
    }

    @Test
    void usesDefaultLimitWhenInputIsNull() {
        assertEquals(20, Cursor.limit(null));
    }

    @Test
    void acceptsValidPageLimits() {
        assertEquals(1, Cursor.limit("1"));
        assertEquals(20, Cursor.limit("20"));
        assertEquals(100, Cursor.limit("100"));
    }

    @Test
    void rejectsInvalidPageLimits() {
        String[] invalid = {"0", "101", "-1", "001", "1.5", "abc", ""};

        for (String value : invalid) {
            assertThrows(ApiFailure.class, () -> Cursor.limit(value));
        }
    }
}
