package gecko.rest.config;

import gecko.rest.persistence.PostgresPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Keeps a fenced PostgreSQL replica out of readiness while its process is still alive. */
@Configuration
@Profile("observability")
class ObservabilityConfig {
    @Bean
    HealthIndicator geckoReplicaHealthIndicator(ObjectProvider<PostgresPersistence> persistence) {
        return () -> {
            var postgres = persistence.getIfAvailable();
            return postgres == null || postgres.checkOwnership() ? Health.up().build() : Health.down().build();
        };
    }
}
