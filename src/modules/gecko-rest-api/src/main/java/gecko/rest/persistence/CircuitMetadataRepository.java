package gecko.rest.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CircuitMetadataRepository extends JpaRepository<CircuitMetadata, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select circuit from CircuitMetadata circuit where circuit.id = :id")
    Optional<CircuitMetadata> lockById(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select circuit from CircuitMetadata circuit where circuit.id = :id and circuit.tenant = :tenant")
    Optional<CircuitMetadata> lockByIdAndTenant(@Param("id") String id, @Param("tenant") String tenant);

    Optional<CircuitMetadata> findByIdAndTenant(String id, String tenant);
    List<CircuitMetadata> findByTenant(String tenant);
    long countByTenant(String tenant);
}
