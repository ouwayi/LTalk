package cn.ltalk.server;

import java.nio.file.Path;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

final class SqliteFixtures {
    private SqliteFixtures() { }

    static Connection connect(Path directory) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve(SqliteDatabase.FILE_NAME).toAbsolutePath());
    }

    static void edit(Path directory, String sql) throws SQLException {
        try (Connection connection = connect(directory);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static Map<String, String> account(Path directory, String id) throws SQLException {
        try (Connection connection = connect(directory);
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM accounts WHERE user_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Map.of();
                Map<String, String> account = new LinkedHashMap<>();
                ResultSetMetaData columns = rows.getMetaData();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    account.put(columns.getColumnName(i), rows.getString(i));
                }
                return account;
            }
        }
    }

    static int count(Path directory, String sql) throws SQLException {
        try (Connection connection = connect(directory);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("Expected a row count");
            return rows.getInt(1);
        }
    }
}
