package gecko.rest.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface CircuitMetadataRepository extends JpaRepository<CircuitMetadata, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select circuit from CircuitMetadata circuit where circuit.id = :id")
    Optional<CircuitMetadata> lockById(String id);
}
