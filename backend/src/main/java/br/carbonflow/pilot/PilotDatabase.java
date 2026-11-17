package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class PilotDatabase {
    interface Query<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final DataSource source;

    PilotDatabase(DataSource source) {
        this.source = source;
    }

    <T> T read(Query<T> query) {
        try (var connection = source.getConnection()) {
            return query.execute(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha no banco do piloto", e);
        }
    }

    <T> T transaction(Query<T> query) {
        return read(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = query.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        });
    }
}
