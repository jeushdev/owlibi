package dev.owlibi.reference.security;

import dev.owlibi.reference.web.ApiFailure;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/** Bounded local rolling counters; no forwarded IP or distributed-abuse claim. */
@Service
public class RateLimits {
    private final Clock clock;
    private final Map<String,ArrayDeque<Instant>> windows=new HashMap<>();
    public RateLimits(Clock clock) { this.clock=clock; }
    public synchronized void attempt(String key,int maximum) {
        Instant now=clock.instant(); prune(now); var window=window(key);
        check(window,maximum,now); window.addLast(now);
    }
    // Serialize check/result to bound concurrent guesses. Local throughput is deliberately modest.
    public synchronized <T> T login(String key,Supplier<T> authentication) {
        Instant now=clock.instant(); prune(now); var window=window(key); check(window,5,now);
        try { T result=authentication.get(); windows.remove(key); return result; }
        catch (AuthenticationException failed) {
            window.addLast(clock.instant()); throw new ApiFailure(401,"UNAUTHENTICATED","Sign in could not be completed");
        }
    }
    private void prune(Instant now) {
        windows.values().forEach(w -> { while (!w.isEmpty() && !w.peekFirst().plusSeconds(60).isAfter(now)) w.removeFirst(); });
        windows.entrySet().removeIf(e -> e.getValue().isEmpty());
    }
    private ArrayDeque<Instant> window(String key) {
        if (!windows.containsKey(key) && windows.size()>=10000) limited(1);
        return windows.computeIfAbsent(key,k -> new ArrayDeque<>());
    }
    private static void check(ArrayDeque<Instant> window,int maximum,Instant now) {
        if (window.size()>=maximum) {
            Duration remaining=Duration.between(now,window.peekFirst().plusSeconds(60));
            limited((int)Math.max(1,remaining.getSeconds()+(remaining.getNano()==0?0:1)));
        }
    }
    private static void limited(int seconds) { throw new ApiFailure(429,"RATE_LIMITED","Please wait before trying again",Map.of(),seconds); }
}
