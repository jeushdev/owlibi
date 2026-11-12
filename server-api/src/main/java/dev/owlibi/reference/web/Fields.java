package dev.owlibi.reference.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class Fields {
    private Fields() {}
    public static BigDecimal points(String value, String field) {
        if (value == null || !value.matches("[0-9]+(\\.[0-9]{1,2})?")) throw ApiFailure.validation(field);
        BigDecimal points = new BigDecimal(value);
        if (points.compareTo(new BigDecimal("0.01")) < 0 || points.compareTo(new BigDecimal("99999999.99")) > 0)
            throw ApiFailure.validation(field);
        return points.setScale(2, RoundingMode.UNNECESSARY);
    }
    public static String text(String value, String field, int limit, boolean trim, boolean required) {
        if (value == null) { if (required) throw ApiFailure.validation(field); return ""; }
        scalar(value);
        if (trim) value = value.strip();
        if (value.indexOf('\0') >= 0 || value.codePointCount(0,value.length()) > limit || (required && value.isBlank()))
            throw ApiFailure.validation(field);
        return value;
    }
    public static String email(String value) {
        String email = text(value,"email",254,true,true).toLowerCase(Locale.ROOT);
        // Local identity syntax only; no DNS, deliverability or email-verification claim.
        if (email.codePointCount(0,email.length()) > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw ApiFailure.validation("email");
        return email;
    }
    public static String password(String value, boolean registration) {
        if (value == null) throw ApiFailure.validation("password");
        scalar(value);
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 72 || bytes < (registration ? 12 : 1)) throw ApiFailure.validation("password");
        return value; // No trimming, normalization or composition policy.
    }
    public static void scalar(String value) {
        for (int i=0; i<value.length(); i++) {
            char c=value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw ApiFailure.malformed();
            } else if (Character.isLowSurrogate(c)) throw ApiFailure.malformed();
        }
    }
}
