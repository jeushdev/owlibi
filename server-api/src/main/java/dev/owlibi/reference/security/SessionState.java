package dev.owlibi.reference.security;

import java.time.*;
import java.util.*;
import dev.owlibi.reference.web.ApiFailure;

/** Requests hold this monitor through filters, controller and transaction commit. */
public final class SessionState {
    public static final String ATTRIBUTE=SessionState.class.getName();
    private AccountPrincipal account;
    private UUID epoch;
    private Instant lastHumanActivity;
    private boolean revoked;
    private final ArrayDeque<Instant> reports=new ArrayDeque<>();
    public AccountPrincipal account() { return account; }
    public UUID epoch() { return epoch; }
    public boolean revoked() { return revoked; }
    public Instant expires() { return lastHumanActivity.plusSeconds(1800); }
    public boolean expired(Instant now) { return account!=null && !now.isBefore(expires()); }
    public void login(AccountPrincipal account,Instant now) {
        this.account=account; epoch=UUID.randomUUID(); lastHumanActivity=now; reports.clear(); revoked=false;
    }
    public void revoke() { revoked=true; }
    public void activity(Instant now) {
        if (revoked || expired(now)) throw new ApiFailure(401,"UNAUTHENTICATED","Sign in is required");
        while (!reports.isEmpty() && !reports.peekFirst().plusSeconds(60).isAfter(now)) reports.removeFirst();
        if (reports.size()>=30) {
            Duration remaining=Duration.between(now,reports.peekFirst().plusSeconds(60));
            throw new ApiFailure(429,"RATE_LIMITED","Please wait before trying again",Map.of(),(int)Math.max(1,remaining.getSeconds()+(remaining.getNano()==0?0:1)));
        }
        reports.addLast(now); lastHumanActivity=now;
    }
}
