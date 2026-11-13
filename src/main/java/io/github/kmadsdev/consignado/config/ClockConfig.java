package io.github.kmadsdev.consignado.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable clock, so tests can fix "now" and date-based rules
 * (simulation expiry, due dates) stay deterministic.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
