package dev.owlibi.reference.hosting;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration
@Profile("hosted")
public class HostedRuntimeConfiguration {
    // Validate before any singleton (including DataSource/readiness) can open a connection.
    @Bean static BeanFactoryPostProcessor hostedPolicy(Environment environment) {
        return factory -> validate(environment);
    }
    private static void validate(Environment environment) {
        HostedPolicy.origin(environment.getRequiredProperty("owlibi.hosted.frontend-origin"));
        HostedPolicy.jdbc(environment.getRequiredProperty("spring.datasource.url"));
        require(environment,"server.servlet.session.cookie.secure", "true");
        require(environment,"server.servlet.session.cookie.http-only", "true");
        require(environment,"server.servlet.session.cookie.same-site", "lax");
        require(environment,"server.servlet.session.cookie.path", "/");
        require(environment,"server.forward-headers-strategy", "none");
        require(environment,"spring.flyway.enabled", "false");
        if (!environment.getProperty("server.servlet.session.cookie.domain", "").isEmpty()
            || !environment.getProperty("spring.flyway.password", "").isEmpty()
            || !environment.getProperty("spring.flyway.user", "").isEmpty()
            || !environment.getProperty("spring.flyway.url", "").isEmpty())
            throw new IllegalArgumentException("Hosted runtime must exclude cookie domain and migration credentials");
    }
    private static void require(Environment environment, String name, String value) {
        if (!value.equals(environment.getProperty(name))) throw new IllegalArgumentException("Unsafe hosted configuration");
    }
}
