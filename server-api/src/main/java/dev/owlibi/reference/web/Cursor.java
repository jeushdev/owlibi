package dev.owlibi.reference.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Canonical, versioned keyset cursor. It confers no membership or snapshot authority. */
public record Cursor(Instant time, UUID id) {
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1|"+time+"|"+id).getBytes(StandardCharsets.US_ASCII));
    }
    public static Cursor decode(String input) {
        if (input == null) return null;
        try {
            if (input.length()>512 || !input.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(input),StandardCharsets.US_ASCII).split("\\|",-1);
            if (parts.length!=3 || !parts[0].equals("1")) throw new IllegalArgumentException();
            Cursor cursor = new Cursor(Instant.parse(parts[1]),UUID.fromString(parts[2]));
            if (!java.sql.Timestamp.from(cursor.time()).toInstant().equals(cursor.time())) throw new IllegalArgumentException();
            if (!cursor.encode().equals(input)) throw new IllegalArgumentException();
            return cursor;
        } catch (RuntimeException failure) { throw ApiFailure.malformed(); }
    }
    public static int limit(String input) {
        if (input == null) return 20;
        try {
            if (!input.matches("[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
            int value=Integer.parseInt(input); if (value>100) throw new IllegalArgumentException(); return value;
        } catch (RuntimeException failure) { throw ApiFailure.malformed(); }
    }
}
