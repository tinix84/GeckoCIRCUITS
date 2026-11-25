package gecko.rest.persistence;

import gecko.rest.model.SimulationResponse.SimulationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SimulationMetadataRepository extends JpaRepository<SimulationMetadata, String> {
    Optional<SimulationMetadata> findByIdAndTenant(String id, String tenant);
    List<SimulationMetadata> findByTenant(String tenant);
    long countByTenantAndStatusIn(String tenant, Collection<SimulationStatus> statuses);
}
