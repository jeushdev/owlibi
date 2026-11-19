package dev.owlibi.reference.config;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
@Configuration
public class ClockConfiguration {
    @Bean public Clock serverClock() { return Clock.systemUTC(); }
}
