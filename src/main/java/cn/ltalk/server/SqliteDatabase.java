package cn.ltalk.server;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.*;

/** One writer per service, with independent SQLite snapshots for history reads. */
final class SqliteDatabase implements AutoCloseable {
    static final String FILE_NAME = "ltalk.db";
    private static final int APPLICATION_ID = 0x4c544c4b;
    private static final int VERSION = 2;
    private final SQLiteDataSource source;
    private final Connection writer;

    SqliteDatabase(Path directory) throws IOException {
        for (String legacy : new String[]{"accounts", "history", "social.json"}) {
            if (Files.exists(directory.resolve(legacy), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("发现旧版文件存储，请用 -Dltalk.dataDir 指定独立的新数据目录");
            }
        }
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        source = new SQLiteDataSource(config);
        source.setUrl("jdbc:sqlite:" + directory.resolve(FILE_NAME).toAbsolutePath());
        writer = open();
        try {
            initialize();
        } catch (IOException | RuntimeException ex) {
            try { close(); } catch (IOException closeFailure) { ex.addSuppressed(closeFailure); }
            throw ex;
        }
    }

    private void initialize() throws IOException {
        write(connection -> {
            try (Statement statement = connection.createStatement()) {
                int version = pragma(statement, "user_version");
                int applicationId = pragma(statement, "application_id");
                if (version == VERSION && applicationId == APPLICATION_ID) return null;
                if (version == 0 && applicationId == 0 && pragma(statement, "schema_version") == 0) {
                    runScript(statement, "sqlite-schema-v1.sql");
                    statement.execute("PRAGMA application_id = " + APPLICATION_ID);
                } else if (version != 1 || applicationId != APPLICATION_ID) {
                    throw new IOException("数据库类型或表结构版本不受支持");
                }
                runScript(statement, "sqlite-migrate-v2.sql");
                statement.execute("PRAGMA user_version = " + VERSION);
                return null;
            }
        });
        try (Statement statement = writer.createStatement();
             ResultSet mode = statement.executeQuery("PRAGMA journal_mode = WAL")) {
            if (!mode.next() || !"wal".equalsIgnoreCase(mode.getString(1))) throw new IOException("无法启用 SQLite WAL");
        } catch (SQLException ex) {
            throw failure(ex);
        }
    }

    private static void runScript(Statement statement, String resource) throws IOException, SQLException {
        try (InputStream schema = SqliteDatabase.class.getResourceAsStream(resource)) {
            if (schema == null) throw new IOException("缺少 SQLite 表结构资源：" + resource);
            String sql = new String(schema.readAllBytes(), StandardCharsets.UTF_8);
            for (String command : sql.split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
        }
    }

    private static int pragma(Statement statement, String name) throws SQLException {
        try (ResultSet rows = statement.executeQuery("PRAGMA " + name)) {
            if (!rows.next()) throw new SQLException("Missing SQLite pragma: " + name);
            return rows.getInt(1);
        }
    }

    Connection open() throws IOException {
        try { return source.getConnection(); }
        catch (SQLException ex) { throw failure(ex); }
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(Connection connection) throws SQLException, IOException;
    }

    <T> T read(Operation<T> operation) throws IOException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            return operation.run(connection);
        } catch (SQLException ex) {
            throw failure(ex);
        }
    }

    synchronized <T> T write(Operation<T> operation) throws IOException {
        try {
            writer.setAutoCommit(false);
            try {
                T result = operation.run(writer);
                writer.commit();
                return result;
            } catch (SQLException | IOException | RuntimeException ex) {
                try { writer.rollback(); }
                catch (SQLException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                    try { writer.close(); } catch (SQLException closeFailure) { ex.addSuppressed(closeFailure); }
                }
                throw ex;
            } finally {
                if (!writer.isClosed()) writer.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw failure(ex);
        }
    }

    static PreparedStatement prepare(Connection connection, String sql, Object... values) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        } catch (SQLException | RuntimeException ex) {
            try { statement.close(); } catch (SQLException closeFailure) { ex.addSuppressed(closeFailure); }
            throw ex;
        }
    }

    static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = prepare(connection, sql, values)) {
            return statement.executeUpdate();
        }
    }

    static IOException failure(SQLException ex) {
        return new IOException("SQLite 存储操作失败", ex);
    }

    @Override public synchronized void close() throws IOException {
        try { writer.close(); }
        catch (SQLException ex) { throw failure(ex); }
    }
}
