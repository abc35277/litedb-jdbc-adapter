package com.litedb.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

public final class LiteDbDriver implements Driver {
    public static final String URL_PREFIX = "jdbc:litedb:";

    /**
     * Full implementation version, read from the jar manifest that build.ps1 fills
     * from Directory.Build.props (the single version source). Falls back to the
     * constant when the class is loaded outside a built jar (unit tests, jshell).
     */
    public static final String VERSION = resolveVersion();
    public static final int MAJOR_VERSION = parseVersionPart(VERSION, 0);
    public static final int MINOR_VERSION = parseVersionPart(VERSION, 1);

    static {
        try {
            DriverManager.registerDriver(new LiteDbDriver());
        } catch (SQLException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static String resolveVersion() {
        Package pkg = LiteDbDriver.class.getPackage();
        String version = pkg == null ? null : pkg.getImplementationVersion();
        return version == null || version.isBlank() ? "1.0.0" : version;
    }

    private static int parseVersionPart(String version, int index) {
        String[] parts = version.split("\\.");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index].trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }

        try {
            LiteDbConfig config = LiteDbConfig.parse(url, info);
            BridgeClient client = BridgeClient.open(config);
            return JdbcObjects.newConnection(config, client);
        } catch (RuntimeException ex) {
            throw new SQLException(ex.getMessage(), "08001", ex);
        }
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith(URL_PREFIX);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        DriverPropertyInfo readOnly = property("readOnly", "Open the database read-only (aliases: readonly, read only)", false);
        readOnly.value = value(info, "readOnly", "false");
        readOnly.choices = new String[] { "false", "true" };

        DriverPropertyInfo connection = property("connection", "LiteDB connection mode (aliases: connectionMode, mode)", false);
        connection.value = value(info, "connection", "shared");
        connection.choices = new String[] { "shared", "direct" };

        DriverPropertyInfo sampleSize = property("columnSampleSize", "Documents sampled for schema inference (aliases: columnSample, sampleSize)", false);
        sampleSize.value = value(info, "columnSampleSize", "200");

        DriverPropertyInfo create = property("create", "Allow creating a new database file if it does not exist", false);
        create.value = value(info, "create", "false");
        create.choices = new String[] { "false", "true" };

        DriverPropertyInfo maxRows = property("maxRows", "Default row limit per query (0 = unlimited; Statement.setMaxRows overrides)", false);
        maxRows.value = value(info, "maxRows", "10000");

        DriverPropertyInfo connectTimeout = property("connectTimeout", "Seconds to wait for the bridge to open the database (0 = wait forever)", false);
        connectTimeout.value = value(info, "connectTimeout", "0");

        return new DriverPropertyInfo[] {
            property("file", "LiteDB database file path (aliases: filename, database, databasePath, path, dataSource)", true),
            property("password", "LiteDB password (aliases: pwd, pass)", false),
            readOnly,
            connection,
            property("bridgePath", "Path to LiteDbJdbc.Bridge.exe, LiteDbJdbc.Bridge.dll, or its containing directory (aliases: bridgepath, bridge)", false),
            sampleSize,
            create,
            maxRows,
            connectTimeout
        };
    }

    @Override
    public int getMajorVersion() {
        return MAJOR_VERSION;
    }

    @Override
    public int getMinorVersion() {
        return MINOR_VERSION;
    }

    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger("com.litedb.jdbc");
    }

    private static DriverPropertyInfo property(String name, String description, boolean required) {
        DriverPropertyInfo property = new DriverPropertyInfo(name, null);
        property.description = description;
        property.required = required;
        return property;
    }

    private static String value(Properties properties, String name, String defaultValue) {
        return properties == null ? defaultValue : properties.getProperty(name, defaultValue);
    }
}
