
package dev.owlibi.reference.web;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class FieldsTest {

    @Test
    void acceptsValidPoints() {
        assertEquals(new BigDecimal("1.00"), Fields.points("1", "points"));
        assertEquals(new BigDecimal("10.50"), Fields.points("10.50", "points"));
    }

    @Test
    void rejectsInvalidPoints() {
        String[] invalid = {
            null, "0", "-1", "1.001", "abc", "100000000"
        };

        for (String value : invalid) {
            assertThrows(
                ApiFailure.class,
                () -> Fields.points(value, "points")
            );
        }
    }

    @Test
    void trimsTextWhenRequested() {
        assertEquals("Hello", Fields.text("  Hello  ", "name", 20, true, true));
    }

    @Test
    void rejectsBlankRequiredText() {
        assertThrows(
            ApiFailure.class,
            () -> Fields.text("   ", "name", 20, true, true)
        );
    }

    @Test
    void acceptsOptionalNullText() {
        assertEquals("", Fields.text(null, "name", 20, true, false));
    }

    @Test
    void normalizesEmailToLowercase() {
        assertEquals("john@example.com", Fields.email("  John@Example.com  "));
    }

    @Test
    void rejectsInvalidEmail() {
        assertThrows(ApiFailure.class, () -> Fields.email("invalid-email"));
        assertThrows(ApiFailure.class, () -> Fields.email("john@"));
    }

    @Test
    void acceptsValidRegistrationPassword() {
        String password = "securePassword123";
        assertEquals(password, Fields.password(password, true));
    }

    @Test
    void rejectsShortRegistrationPassword() {
        assertThrows(
            ApiFailure.class,
            () -> Fields.password("short", true)
        );
    }

    @Test
    void rejectsPasswordExceedingByteLimit() {
        String password = "a".repeat(73);

        assertThrows(
            ApiFailure.class,
            () -> Fields.password(password, false)
        );
    }

    @Test
    void rejectsMalformedUnicode() {
        assertThrows(
            ApiFailure.class,
            () -> Fields.scalar("\uD800")
        );
    }
}
