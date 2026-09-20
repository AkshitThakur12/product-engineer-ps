package com.example.reminders.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides an injectable Clock bean.
 *
 * <p>Production: Clock.systemUTC()
 * <p>Tests: Override with Clock.fixed(...) or a controllable clock.
 *
 * <p>All time-dependent decisions must use this Clock instead of
 * Instant.now() or System.currentTimeMillis().
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
