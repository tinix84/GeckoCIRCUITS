package gecko.rest.persistence;

import gecko.rest.model.SimulationResponse.SimulationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SimulationMetadataRepository extends JpaRepository<SimulationMetadata, String> {
    Optional<SimulationMetadata> findByIdAndTenant(String id, String tenant);
    List<SimulationMetadata> findByTenant(String tenant);
    long countByTenantAndStatusIn(String tenant, Collection<SimulationStatus> statuses);

    @Modifying
    @Query("""
            update SimulationMetadata job set job.status = :status, job.finishedAt = :finished, job.error = :error
             where job.id = :id and job.replicaId = :replica and job.status in :active
            """)
    int updateActiveJob(@Param("id") String id, @Param("replica") int replica, @Param("status") SimulationStatus status,
                        @Param("finished") Instant finished, @Param("error") String error,
                        @Param("active") Collection<SimulationStatus> active);

    void deleteByIdAndReplicaId(String id, int replicaId);
}
