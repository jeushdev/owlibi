package dev.owlibi.reference.config;

import dev.owlibi.reference.security.*;
import dev.owlibi.reference.web.ApiFailure;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import dev.owlibi.reference.hosting.HostedOriginFilter;
import org.springframework.security.authentication.*;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.authentication.session.*;

@Configuration
public class WebBoundaryConfiguration {
    @Bean PasswordEncoder passwords() { return new BCryptPasswordEncoder(12); }
    @Bean AuthenticationManager accounts(JdbcTemplate jdbc,PasswordEncoder passwords) {
        UserDetailsService users=email -> {
            var found=jdbc.query("SELECT id,display_name,password_hash FROM users WHERE normalized_email=?",(rs,n) ->
                new LocalUser(new AccountPrincipal(rs.getObject("id",java.util.UUID.class),rs.getString("display_name")),email,rs.getString("password_hash")),email);
            if (found.isEmpty()) throw new UsernameNotFoundException("Account unavailable");
            return found.getFirst();
        };
        var provider=new DaoAuthenticationProvider(users); provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }
    public static final class LocalUser extends User {
        private final AccountPrincipal account;
        LocalUser(AccountPrincipal account,String email,String hash) { super(email,hash,List.of()); this.account=account; }
        public AccountPrincipal account() { return account; }
    }
    @Bean HttpSessionCsrfTokenRepository csrfTokens() { return new HttpSessionCsrfTokenRepository(); }
    @Bean HttpSessionSecurityContextRepository contexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean SessionAuthenticationStrategy loginStrategy(HttpSessionCsrfTokenRepository tokens) {
        return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),new CsrfAuthenticationStrategy(tokens)));
    }
    @Bean SecurityFilterChain boundary(HttpSecurity http,Clock clock,HttpSessionCsrfTokenRepository tokens,HttpSessionSecurityContextRepository contexts,Environment environment) throws Exception {
        http.addFilterBefore(new SessionBoundaryFilter(clock),CsrfFilter.class);
        if (environment.acceptsProfiles(Profiles.of("hosted")))
            http.addFilterAfter(new HostedOriginFilter(environment.getRequiredProperty("owlibi.hosted.frontend-origin")),SessionBoundaryFilter.class);
        return http.securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
            .csrf(csrf -> csrf.csrfTokenRepository(tokens).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {
                @Override public String resolveCsrfTokenValue(jakarta.servlet.http.HttpServletRequest request,CsrfToken token) {
                    return request.getHeader(token.getHeaderName()); // Never accept a token in a URL or form parameter.
                }
            }))
            .addFilterAfter(new SessionEpochFilter(clock),CsrfFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/main.js", "/styles.css", "/api.js", "/session.js", "/model.js", "/ui.js", "/brand-logo.png", "/mockup", "/mockup/", "/mockup/**", "/api/v1/health", "/api/v1/auth/accounts", "/api/v1/auth/sessions").permitAll()
                .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
            .requestCache(cache -> cache.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request,response,failure) -> SessionBoundaryFilter.write(response,new ApiFailure(401,"UNAUTHENTICATED","Sign in is required")))
                .accessDeniedHandler((request,response,failure) -> SessionBoundaryFilter.write(response,
                    failure instanceof CsrfException ? new ApiFailure(403,"CSRF_INVALID","Request verification failed") : new ApiFailure(403,"FORBIDDEN","Action unavailable"))))
            .headers(headers -> headers.addHeaderWriter((request,response) -> {
                String path=request.getServletPath();
                boolean prototype=path.equals("/mockup") || path.startsWith("/mockup/");
                response.setHeader("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'"
                    +(prototype?" 'unsafe-inline'":"")+"; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            }))
            .build();
    }
}
