package dev.owlibi.config;

import dev.owlibi.hosting.HostedOriginFilter;
import dev.owlibi.security.AccountPrincipal;
import dev.owlibi.security.SessionBoundaryFilter;
import dev.owlibi.security.SessionEpochFilter;
import dev.owlibi.web.ApiFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/**
 * The security boundary of the web app: password sign-in, session and CSRF handling,
 * which paths are public, and the JSON errors and headers every response carries.
 */
@Configuration
public class WebBoundaryConfiguration {
    /** BCrypt password hashing with cost 12. */
    @Bean
    PasswordEncoder passwords() {
        return new BCryptPasswordEncoder(12);
    }

    /** Checks an email and password against the users table. */
    @Bean
    AuthenticationManager accounts(JdbcTemplate jdbc, PasswordEncoder passwords) {
        UserDetailsService users = email -> {
            var found = jdbc.query(
                "SELECT id,display_name,password_hash FROM users WHERE normalized_email=?",
                (row, rowNumber) -> new LocalUser(
                    new AccountPrincipal(row.getObject("id", UUID.class), row.getString("display_name")),
                    email,
                    row.getString("password_hash")
                ),
                email
            );
            if (found.isEmpty()) throw new UsernameNotFoundException("Account unavailable");
            return found.getFirst();
        };
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    /** A signed-in Spring Security user that also carries the Owlibi account it belongs to. */
    public static final class LocalUser extends User {
        private final AccountPrincipal account;

        LocalUser(AccountPrincipal account, String email, String hash) {
            super(email, hash, List.of());
            this.account = account;
        }

        /** The Owlibi account behind this user. */
        public AccountPrincipal account() {
            return account;
        }
    }

    /** Stores the CSRF token in the server-side session. */
    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    /** Stores the signed-in security context in the server-side session. */
    @Bean
    HttpSessionSecurityContextRepository contexts() {
        return new HttpSessionSecurityContextRepository();
    }

    /** On sign-in, changes the session id and issues a fresh CSRF token. */
    @Bean
    SessionAuthenticationStrategy loginStrategy(HttpSessionCsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(
            new ChangeSessionIdAuthenticationStrategy(),
            new CsrfAuthenticationStrategy(tokens)
        ));
    }

    /** The filter chain: session filters, CSRF from a header only, public paths, JSON errors and the CSP header. */
    @Bean
    SecurityFilterChain boundary(
        HttpSecurity http,
        Clock clock,
        HttpSessionCsrfTokenRepository tokens,
        HttpSessionSecurityContextRepository contexts,
        Environment environment
    ) throws Exception {
        http.addFilterBefore(new SessionBoundaryFilter(clock), CsrfFilter.class);
        if (environment.acceptsProfiles(Profiles.of("hosted"))) {
            http.addFilterAfter(
                new HostedOriginFilter(environment.getRequiredProperty("owlibi.hosted.frontend-origin")),
                SessionBoundaryFilter.class
            );
        }
        return http
            .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(csrf -> csrf
                .csrfTokenRepository(tokens)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {
                    @Override
                    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
                        return request.getHeader(token.getHeaderName()); // Never accept a token in a URL or form parameter.
                    }
                }))
            .addFilterAfter(new SessionEpochFilter(clock), CsrfFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/index.html", "/main.js", "/styles.css", "/api.js", "/session.js", "/model.js", "/ui.js",
                    "/brand-logo.png", "/mockup", "/mockup/", "/mockup/**",
                    "/api/v1/health", "/api/v1/auth/accounts", "/api/v1/auth/sessions"
                ).permitAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll())
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) ->
                    SessionBoundaryFilter.write(response, new ApiFailure(401, "UNAUTHENTICATED", "Sign in is required")))
                .accessDeniedHandler((request, response, failure) -> SessionBoundaryFilter.write(
                    response,
                    failure instanceof CsrfException
                        ? new ApiFailure(403, "CSRF_INVALID", "Request verification failed")
                        : new ApiFailure(403, "FORBIDDEN", "Action unavailable")
                )))
            .headers(headers -> headers.addHeaderWriter((request, response) -> {
                String path = request.getServletPath();
                boolean prototype = path.equals("/mockup") || path.startsWith("/mockup/");
                response.setHeader(
                    "Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'"
                        + (prototype ? " 'unsafe-inline'" : "")
                        + "; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
                );
            }))
            .build();
    }
}
