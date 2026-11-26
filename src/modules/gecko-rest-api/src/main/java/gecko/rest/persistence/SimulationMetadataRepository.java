package gecko.rest.persistence;

import gecko.rest.model.SimulationResponse.SimulationStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SimulationMetadataRepository extends JpaRepository<SimulationMetadata, String> {
    Optional<SimulationMetadata> findByIdAndTenant(String id, String tenant);
    List<SimulationMetadata> findByTenant(String tenant);
    long countByTenantAndStatusIn(String tenant, Collection<SimulationStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select job from SimulationMetadata job
             where job.id = :id and job.replicaId = :replica and job.status in :active
            """)
    Optional<SimulationMetadata> lockActiveJob(@Param("id") String id, @Param("replica") int replica,
                                               @Param("active") Collection<SimulationStatus> active);

    void deleteByIdAndReplicaId(String id, int replicaId);
}
