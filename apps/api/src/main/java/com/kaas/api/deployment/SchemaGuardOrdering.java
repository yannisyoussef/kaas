package com.kaas.api.deployment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jpa.autoconfigure.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts {@link SchemaGuard} ahead of JPA, the way Spring Boot puts Flyway ahead of it when migration is on.
 *
 * <p>Without this the entity manager factory validates the schema first and a database the migrator never ran
 * against fails with "missing table [...]": closed, but naming a symptom rather than the cause. With it the
 * refusal is {@code SCHEMA_NOT_CURRENT}, which tells an operator exactly which step of the deployment was skipped.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
class SchemaGuardOrdering {

    @Bean
    static EntityManagerFactoryDependsOnPostProcessor schemaGuardBeforeJpa() {
        return new EntityManagerFactoryDependsOnPostProcessor(SchemaGuard.class);
    }
}
