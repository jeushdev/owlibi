package dev.owlibi.security;

import dev.owlibi.web.ApiFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;

/**
 * The signed-in account of one browser session and when it expires.
 *
 * <p>A session expires 30 minutes after the person's last activity. Requests hold this object's monitor
 * through filters, controller and transaction commit.
 */
public final class SessionState {
    /** The request and session attribute name this state is stored under. */
    public static final String ATTRIBUTE = SessionState.class.getName();
    private static final long IDLE_SECONDS = 1800;
    private static final int MAXIMUM_ACTIVITY_REPORTS_PER_MINUTE = 30;

    private AccountPrincipal account;
    private UUID epoch;
    private Instant lastHumanActivity;
    private boolean revoked;
    private final ArrayDeque<Instant> reports = new ArrayDeque<>();

    /** The signed-in account, or {@code null} before sign-in. */
    public AccountPrincipal account() {
        return account;
    }

    /** A random id that changes at every sign-in. */
    public UUID epoch() {
        return epoch;
    }

    /** Whether the session was signed out. */
    public boolean revoked() {
        return revoked;
    }

    /** When the session expires if there is no more activity. */
    public Instant expires() {
        return lastHumanActivity.plusSeconds(IDLE_SECONDS);
    }

    /** Whether a signed-in session has passed its expiry at {@code now}. */
    public boolean expired(Instant now) {
        return account != null && !now.isBefore(expires());
    }

    /** Signs {@code account} in, with a new epoch. */
    public void login(AccountPrincipal account, Instant now) {
        this.account = account;
        epoch = UUID.randomUUID();
        lastHumanActivity = now;
        reports.clear();
        revoked = false;
    }

    /** Signs the session out. */
    public void revoke() {
        revoked = true;
    }

    /** Records that the person is still active, extending the session. Limited to 30 reports a minute. */
    public void activity(Instant now) {
        if (revoked || expired(now)) {
            throw new ApiFailure(401, "UNAUTHENTICATED", "Sign in is required");
        }
        while (!reports.isEmpty() && !reports.peekFirst().plusSeconds(60).isAfter(now)) {
            reports.removeFirst();
        }
        if (reports.size() >= MAXIMUM_ACTIVITY_REPORTS_PER_MINUTE) {
            Duration remaining = Duration.between(now, reports.peekFirst().plusSeconds(60));
            int retrySeconds = (int) Math.max(1, remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1));
            throw new ApiFailure(429, "RATE_LIMITED", "Please wait before trying again", Map.of(), retrySeconds);
        }
        reports.addLast(now);
        lastHumanActivity = now;
    }
}
