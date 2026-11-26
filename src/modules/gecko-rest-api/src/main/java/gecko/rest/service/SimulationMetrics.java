package gecko.rest.service;

import gecko.rest.model.SimulationResponse;
import gecko.rest.model.SimulationResponse.SimulationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Measures terminal jobs and the bounded simulation executor. */
@Component
@Profile("observability")
class SimulationMetrics {
    private final MeterRegistry registry;
    private final Counter completed;
    private final Counter failed;
    private final Timer duration;

    SimulationMetrics(MeterRegistry registry) {
        this.registry = registry;
        completed = registry.counter("gecko.simulations", "status", "COMPLETED");
        failed = registry.counter("gecko.simulations", "status", "FAILED");
        duration = Timer.builder("gecko.simulation.duration")
                .description("Time from job creation to its terminal state")
                .publishPercentileHistogram()
                .register(registry);
    }

    void bind(ThreadPoolExecutor executor) {
        Gauge.builder("gecko.simulations.active", executor, ThreadPoolExecutor::getActiveCount).register(registry);
        Gauge.builder("gecko.simulation.queue.depth", executor, pool -> pool.getQueue().size()).register(registry);
    }

    void recordTerminal(SimulationResponse response) {
        if (response.getStatus() == SimulationStatus.COMPLETED) completed.increment();
        else failed.increment();
        if (response.getStartTime() != null && response.getEndTime() != null) {
            Duration elapsed = Duration.between(response.getStartTime(), response.getEndTime());
            if (!elapsed.isNegative()) duration.record(elapsed);
        }
    }
}
