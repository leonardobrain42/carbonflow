package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class PilotSql {
    interface Row<T> {
        T map(ResultSet row) throws SQLException;
    }

    static <T> List<T> query(Connection connection, String sql, Row<T> mapping, Object... arguments) throws SQLException {
        var result = new ArrayList<T>();
        try (var statement = prepare(connection, sql, arguments); var rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(mapping.map(rows));
            }
        }
        return result;
    }

    static <T> T first(Connection connection, String sql, Row<T> mapping, Object... arguments) throws SQLException {
        try (var statement = prepare(connection, sql, arguments); var rows = statement.executeQuery()) {
            return rows.next() ? mapping.map(rows) : null;
        }
    }

    static int update(Connection connection, String sql, Object... arguments) throws SQLException {
        try (var statement = prepare(connection, sql, arguments)) {
            return statement.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... arguments) throws SQLException {
        var statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            return statement;
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
    }
}
