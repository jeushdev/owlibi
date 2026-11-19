package dev.owlibi.reference.support;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Explicit test source only; never component-scanned into the production application. */
public final class MutableClock extends Clock {
    public static final MutableClock INSTANCE=new MutableClock();
    private final java.util.concurrent.atomic.AtomicInteger countdown=new java.util.concurrent.atomic.AtomicInteger();
    private long jumpSeconds;
    public void advanceOnCall(int call,long seconds) { jumpSeconds=seconds; countdown.set(call); }
    private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-10T10:00:00Z"));
    public void reset() { countdown.set(0); now.set(Instant.parse("2026-10-10T10:00:00Z")); }
    public void advanceNanos(long nanos) { now.updateAndGet(i -> i.plusNanos(nanos)); }
    public void advance(long seconds) { now.updateAndGet(i -> i.plusSeconds(seconds)); }
    @Override public Instant instant() { if(countdown.get()>0 && countdown.decrementAndGet()==0) advance(jumpSeconds); return now.get(); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    public static class Configuration {
        @Bean @Primary public Clock mutableTestClock() { return INSTANCE; }
    }
    public static final String SOURCE="--spring.main.sources=dev.owlibi.reference.support.MutableClock$Configuration";
}
