package dev.owlibi.reference.security;

import dev.owlibi.reference.web.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final AuthenticationManager authentication;
    private final RateLimits rates;
    private final Clock clock;
    public AccountService(JdbcTemplate jdbc,PasswordEncoder encoder,AuthenticationManager authentication,RateLimits rates,Clock clock) {
        this.jdbc=jdbc; this.encoder=encoder; this.authentication=authentication; this.rates=rates; this.clock=clock;
    }
    public void registrationAttempt(String peer) { rates.attempt("register:"+peer,20); }
    @Transactional public AccountPrincipal register(String email,String displayName,String password) {
        email=Fields.email(email); displayName=Fields.text(displayName,"displayName",100,true,true); password=Fields.password(password,true);
        UUID id=UUID.randomUUID();
        try { jdbc.update("INSERT INTO users (id,normalized_email,display_name,password_hash,created_at_utc) VALUES (?,?,?,?,?)",
            id,email,displayName,encoder.encode(password),Timestamp.from(clock.instant())); }
        catch (DuplicateKeyException conflict) { throw new ApiFailure(409,"ACCOUNT_UNAVAILABLE","Account could not be created"); }
        return new AccountPrincipal(id,displayName);
    }
    public Authentication login(String email,String password,String peer) {
        String normalized=Fields.email(email),exact=Fields.password(password,false);
        return rates.login("login:"+peer+":"+normalized,() -> authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(normalized,exact)));
    }
}
