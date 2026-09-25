package com.ledgerlite.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Thin JDBC connection factory plus schema bootstrap.
 *
 * <p>Two dialects are supported: MySQL (production) and embedded Apache Derby
 * (zero-setup demo mode and tests). The dialect is inferred from the JDBC URL.</p>
 */
public final class Database {

    public enum Dialect { MYSQL, DERBY }

    private final String url;
    private final String user;
    private final String password;
    private final Dialect dialect;

    public Database(String url, String user, String password) {
        this.url = Objects.requireNonNull(url, "url");
        this.user = user;
        this.password = password;
        this.dialect = url.startsWith("jdbc:mysql:") ? Dialect.MYSQL : Dialect.DERBY;
    }

    /** In-memory Derby database, handy for demos and tests. */
    public static Database inMemory(String name) {
        return new Database("jdbc:derby:memory:" + name + ";create=true", null, null);
    }

    /**
     * Reads LEDGERLITE_DB_URL / LEDGERLITE_DB_USER / LEDGERLITE_DB_PASSWORD, falling back
     * to an in-memory Derby database when no URL is configured.
     */
    public static Database fromEnvironment() {
        String url = System.getenv("LEDGERLITE_DB_URL");
        if (url == null || url.isBlank()) {
            return inMemory("ledgerlite");
        }
        return new Database(url, System.getenv("LEDGERLITE_DB_USER"), System.getenv("LEDGERLITE_DB_PASSWORD"));
    }

    public Connection getConnection() throws SQLException {
        return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Creates tables if they do not exist yet. Safe to call repeatedly. */
    public void initSchema() {
        String resource = dialect == Dialect.MYSQL ? "/schema-mysql.sql" : "/schema-derby.sql";
        String script = readResource(resource);
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            for (String sql : stripComments(script).split(";")) {
                String stmt = sql.trim();
                if (stmt.isEmpty()) {
                    continue;
                }
                try {
                    st.execute(stmt);
                } catch (SQLException e) {
                    // Derby has no "IF NOT EXISTS": X0Y32 = object already exists.
                    if (!"X0Y32".equals(e.getSQLState())) {
                        throw e;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise schema", e);
        }
    }

    private static String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }

    private static String readResource(String name) {
        try (InputStream in = Database.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + name, e);
        }
    }
}
