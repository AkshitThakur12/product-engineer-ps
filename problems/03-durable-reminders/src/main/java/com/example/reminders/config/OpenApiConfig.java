package com.example.reminders.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger UI configuration.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Durable Reminders API")
                        .version("1.0.0")
                        .description("""
                                Durable Reminders and Scheduled Follow-Ups service.
                                
                                Features:
                                - Create, inspect, edit, and cancel scheduled reminders
                                - Durable scheduling with database as source of truth
                                - IANA timezone support with deterministic DST handling
                                - Bounded retry with attempt history
                                - Idempotent delivery via delivery keys
                                - Restart recovery for overdue work
                                - Race handling for edit/cancellation vs execution
                                """));
    }
}
