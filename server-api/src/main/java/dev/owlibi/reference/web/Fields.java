package dev.owlibi.reference.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Validation for the plain text and number fields that requests send.
 *
 * <p>Every method either returns a clean value or throws an {@link ApiFailure} naming the bad field,
 * so services can validate input in one line per field.
 */
public final class Fields {
    private static final BigDecimal MINIMUM_POINTS = new BigDecimal("0.01");
    private static final BigDecimal MAXIMUM_POINTS = new BigDecimal("99999999.99");

    private Fields() {}

    /** Parses effort points such as {@code "2.5"} into a two-decimal amount between 0.01 and 99,999,999.99. */
    public static BigDecimal points(String value, String field) {
        if (value == null || !value.matches("[0-9]+(\\.[0-9]{1,2})?")) {
            throw ApiFailure.validation(field);
        }
        BigDecimal points = new BigDecimal(value);
        if (points.compareTo(MINIMUM_POINTS) < 0 || points.compareTo(MAXIMUM_POINTS) > 0) {
            throw ApiFailure.validation(field);
        }
        return points.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * Validates a text field.
     *
     * @param value the submitted text, or {@code null} when it was left out
     * @param field the field name reported if the value is invalid
     * @param limit the maximum number of characters (Unicode code points)
     * @param trim whether to remove surrounding whitespace first
     * @param required whether a missing or blank value is an error; otherwise it becomes {@code ""}
     */
    public static String text(String value, String field, int limit, boolean trim, boolean required) {
        if (value == null) {
            if (required) {
                throw ApiFailure.validation(field);
            }
            return "";
        }
        scalar(value);
        if (trim) {
            value = value.strip();
        }
        boolean hasNullCharacter = value.indexOf('\0') >= 0;
        boolean tooLong = value.codePointCount(0, value.length()) > limit;
        if (hasNullCharacter || tooLong || (required && value.isBlank())) {
            throw ApiFailure.validation(field);
        }
        return value;
    }

    /**
     * Validates optional free text, such as a description, kept exactly as typed.
     *
     * <p>This overload covers the most common case: the value may be missing (it becomes {@code ""}) and
     * its whitespace is kept. Java chooses between the two {@code text} methods at compile time from the
     * number of arguments.
     */
    public static String text(String value, String field, int limit) {
        return text(value, field, limit, false, false);
    }

    /** Validates and lowercases an email address. Only its shape is checked, not whether it exists. */
    public static String email(String value) {
        String email = text(value, "email", 254, true, true).toLowerCase(Locale.ROOT);
        // Local identity syntax only; no DNS, deliverability or email-verification claim.
        if (email.codePointCount(0, email.length()) > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw ApiFailure.validation("email");
        }
        return email;
    }

    /** Checks a password's length in bytes. New accounts need at least 12 bytes; sign-in accepts any non-empty value. */
    public static String password(String value, boolean registration) {
        if (value == null) {
            throw ApiFailure.validation("password");
        }
        scalar(value);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        int minimum = registration ? 12 : 1;
        if (bytes > 72 || bytes < minimum) {
            throw ApiFailure.validation("password");
        }
        return value; // No trimming, normalization or composition policy.
    }

    /** Rejects text with a broken UTF-16 surrogate pair, which cannot be stored or encoded safely. */
    public static void scalar(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                index++;
                if (index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    throw ApiFailure.malformed();
                }
            } else if (Character.isLowSurrogate(current)) {
                throw ApiFailure.malformed();
            }
        }
    }
}
