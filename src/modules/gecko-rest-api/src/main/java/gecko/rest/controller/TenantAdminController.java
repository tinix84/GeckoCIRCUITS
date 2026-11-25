package gecko.rest.controller;

import gecko.rest.config.ApiKeyProperties;
import gecko.rest.service.CircuitFileService;
import gecko.rest.service.SimulationService;
import java.util.List;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("tenant")
public class TenantAdminController {
    private final ApiKeyProperties keys;
    private final CircuitFileService circuits;
    private final SimulationService simulations;

    public TenantAdminController(ApiKeyProperties keys, CircuitFileService circuits, SimulationService simulations) {
        this.keys = keys;
        this.circuits = circuits;
        this.simulations = simulations;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/api/v1/admin/tenants")
    public List<TenantUsage> tenants() {
        var tenants = new TreeSet<>(keys.getQuota().keySet());
        keys.getKeys().stream().map(gecko.rest.config.ApiIdentity::keyId).map(keys::tenantFor)
                .filter(java.util.Objects::nonNull).forEach(tenants::add);
        return tenants.stream().map(tenant -> {
            var quota = keys.quotaFor(tenant);
            return new TenantUsage(tenant, circuits.circuitCount(tenant), simulations.activeJobs(tenant),
                    quota.getMaxCircuits(), quota.getMaxConcurrent());
        }).toList();
    }

    public record TenantUsage(String tenant, long circuits, long activeSimulations, int maxCircuits, int maxConcurrent) { }
}
