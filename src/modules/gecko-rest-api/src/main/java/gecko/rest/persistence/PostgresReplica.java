package gecko.rest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.stereotype.Component;

/** Holds the PostgreSQL session that owns this process's active jobs. */
@Component
@Profile("postgres")
@DependsOnDatabaseInitialization
final class PostgresReplica {
    private static final Logger logger = LoggerFactory.getLogger(PostgresReplica.class);
    private final Connection session;
    private final DataSource source;
    private volatile boolean lost;
    private final int namespace;
    private final int id;

    PostgresReplica(DataSource source) throws SQLException {
        if (source instanceof HikariDataSource pool && pool.getMaximumPoolSize() == 1) {
            throw new IllegalStateException("PostgreSQL replica ownership requires at least two pool connections.");
        }
        this.source = source;
        Connection connection = source.getConnection();
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            connection.close();
            throw e;
        }
        try (var statement = connection.createStatement();
             var identity = statement.executeQuery("""
                     select oid::integer, nextval('simulation_replica_id')
                       from pg_namespace where nspname = current_schema()
                     """)) {
            if (!identity.next()) throw new SQLException("Cannot identify the PostgreSQL job schema.");
            namespace = identity.getInt(1);
            id = identity.getInt(2);
            try (var lock = connection.prepareStatement("select pg_advisory_lock(?, ?)")) {
                lock.setInt(1, namespace);
                lock.setInt(2, id);
                lock.execute();
            }
        } catch (SQLException e) {
            try {
                connection.abort(Runnable::run);
            } catch (SQLException abortFailure) {
                e.addSuppressed(abortFailure);
            }
            throw e;
        }
        session = connection;
    }

    int namespace() { return namespace; }

    boolean hasOwnership() { return !lost; }

    synchronized boolean checkOwnership() {
        if (lost) return false;
        try {
            if (session.isValid(2)) return true;
        } catch (SQLException e) {
            logger.debug("PostgreSQL ownership validation failed.", e);
        }
        relinquishOwnership();
        return false;
    }

    synchronized void relinquishOwnership() {
        if (lost) return;
        lost = true;
        logger.error("PostgreSQL replica ownership released. Restart the API.");
        try (session) {
            if (source instanceof HikariDataSource pool) pool.evictConnection(session);
            else session.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            logger.warn("Could not release the PostgreSQL ownership session.", e);
        }
    }

    int liveId() {
        if (!checkOwnership()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Simulation ownership lost. Restart the API.");
        }
        return id;
    }

    int id() { return id; }

    @PreDestroy
    synchronized void close() throws SQLException {
        if (lost) return;
        lost = true;
        try (session; var unlock = session.prepareStatement("select pg_advisory_unlock(?, ?)")) {
            unlock.setInt(1, namespace);
            unlock.setInt(2, id);
            unlock.execute();
        }
    }
}
