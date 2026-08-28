package com.kaas.api;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ApplicationConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
