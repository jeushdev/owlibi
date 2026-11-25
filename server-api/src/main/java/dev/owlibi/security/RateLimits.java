package dev.owlibi.security;

import dev.owlibi.web.ApiFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * Counts attempts per key over a rolling 60-second window and refuses extra ones with 429.
 *
 * <p>The counters live in this server's memory and are bounded in size. They make no claim about
 * forwarded IP addresses or distributed abuse.
 */
@Service
public class RateLimits {
    private static final int WINDOW_SECONDS = 60;
    private static final int MAXIMUM_KEYS = 10000;
    private static final int MAXIMUM_FAILED_LOGINS = 5;

    private final Clock clock;
    private final Map<String, ArrayDeque<Instant>> windows = new HashMap<>();

    public RateLimits(Clock clock) {
        this.clock = clock;
    }

    /** Counts one attempt under {@code key}, refusing it when {@code maximum} were already made this minute. */
    public synchronized void attempt(String key, int maximum) {
        Instant now = clock.instant();
        prune(now);
        var window = window(key);
        check(window, maximum, now);
        window.addLast(now);
    }

    /**
     * Runs a sign-in, allowing five failures a minute per key. A success clears the key's failures.
     *
     * <p>Serialize check/result to bound concurrent guesses. Local throughput is deliberately modest.
     */
    public synchronized <T> T login(String key, Supplier<T> authentication) {
        Instant now = clock.instant();
        prune(now);
        var window = window(key);
        check(window, MAXIMUM_FAILED_LOGINS, now);
        try {
            T result = authentication.get();
            windows.remove(key);
            return result;
        } catch (AuthenticationException failed) {
            window.addLast(clock.instant());
            throw new ApiFailure(401, "UNAUTHENTICATED", "Sign in could not be completed");
        }
    }

    /** Drops attempts older than the window, and keys with none left. */
    private void prune(Instant now) {
        windows.values().forEach(window -> {
            while (!window.isEmpty() && !window.peekFirst().plusSeconds(WINDOW_SECONDS).isAfter(now)) {
                window.removeFirst();
            }
        });
        windows.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    private ArrayDeque<Instant> window(String key) {
        if (!windows.containsKey(key) && windows.size() >= MAXIMUM_KEYS) {
            limited(1);
        }
        return windows.computeIfAbsent(key, unused -> new ArrayDeque<>());
    }

    private static void check(ArrayDeque<Instant> window, int maximum, Instant now) {
        if (window.size() >= maximum) {
            Duration remaining = Duration.between(now, window.peekFirst().plusSeconds(WINDOW_SECONDS));
            limited((int) Math.max(1, remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1)));
        }
    }

    private static void limited(int seconds) {
        throw new ApiFailure(429, "RATE_LIMITED", "Please wait before trying again", Map.of(), seconds);
    }
}
