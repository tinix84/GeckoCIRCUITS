package gecko.rest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/** Holds the PostgreSQL session that owns this process's active jobs. */
@Component
@Profile("postgres")
@DependsOnDatabaseInitialization
final class PostgresReplica {
    private final Connection session;
    private final int namespace;
    private final int id;

    PostgresReplica(DataSource source) throws SQLException {
        if (source instanceof HikariDataSource pool && pool.getMaximumPoolSize() == 1) {
            throw new IllegalStateException("PostgreSQL replica ownership requires at least two pool connections.");
        }
        Connection connection = source.getConnection();
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

    synchronized int liveId() {
        try {
            if (session.isValid(2)) return id;
        } catch (SQLException e) {
            throw new DataAccessResourceFailureException("PostgreSQL replica ownership session failed.", e);
        }
        throw new DataAccessResourceFailureException("PostgreSQL replica ownership session closed. Restart the API.");
    }

    @PreDestroy
    synchronized void close() throws SQLException {
        try (session; var unlock = session.prepareStatement("select pg_advisory_unlock(?, ?)")) {
            unlock.setInt(1, namespace);
            unlock.setInt(2, id);
            unlock.execute();
        }
    }
}
