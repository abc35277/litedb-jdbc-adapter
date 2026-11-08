import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

public final class SmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SmokeTest <jdbc-url> [bridge-path] [password]");
            System.exit(2);
        }

        Class.forName("com.litedb.jdbc.LiteDbDriver");
        Properties properties = new Properties();
        if (args.length >= 2) {
            properties.setProperty("bridgePath", args[1]);
        }
        if (args.length >= 3) {
            properties.setProperty("password", args[2]);
        }

        try (Connection connection = DriverManager.getConnection(args[0], properties)) {
            require(connection.isValid(1), "connection should validate through bridge ping");
            DatabaseMetaData metaData = connection.getMetaData();
            System.out.println("Database: " + metaData.getDatabaseProductName() + " " + metaData.getDatabaseProductVersion());
            require(" ".equals(metaData.getIdentifierQuoteString()), "driver should tell DBeaver not to quote LiteDB identifiers");

            Set<String> tableNames = new HashSet<>();
            try (ResultSet tables = metaData.getTables(null, null, "%", new String[] { "TABLE" })) {
                while (tables.next()) {
                    String table = tables.getString("TABLE_NAME");
                    tableNames.add(table);
                    System.out.println("Table: " + table);
                }
            }
            require(tableNames.contains("people"), "metadata should expose the people collection");
            require(tableNames.contains("orders"), "metadata should expose the orders collection");

            int peopleColumns = 0;
            try (ResultSet columns = metaData.getColumns(null, null, "people", "%")) {
                ResultSetMetaData columnsMeta = columns.getMetaData();
                require(columnsMeta.getColumnType(5) == java.sql.Types.INTEGER, "metadata DATA_TYPE column should be INTEGER");
                while (columns.next()) {
                    peopleColumns++;
                    require(columns.getString("TABLE_NAME").equals("people"), "column metadata table name should match");
                }
            }
            require(peopleColumns >= 4, "metadata should infer columns from people documents");

            try (ResultSet keys = metaData.getPrimaryKeys(null, null, "people")) {
                require(keys.next(), "metadata should expose _id as a primary key");
                require("_id".equals(keys.getString("COLUMN_NAME")), "primary key should be _id");
            }

            try (ResultSet keys = metaData.getImportedKeys(null, null, "people")) {
                require(keys.getMetaData().getColumnCount() == 14, "foreign-key metadata should expose JDBC columns");
            }

            try (ResultSet rowId = metaData.getBestRowIdentifier(null, null, "people", DatabaseMetaData.bestRowSession, true)) {
                require(rowId.next(), "best row identifier should expose _id");
                require("_id".equals(rowId.getString("COLUMN_NAME")), "best row identifier should be _id");
            }

            boolean hasNameIndex = false;
            try (ResultSet indexes = metaData.getIndexInfo(null, null, "people", false, false)) {
                while (indexes.next()) {
                    if ("idx_name".equals(indexes.getString("INDEX_NAME"))) {
                        hasNameIndex = true;
                        require("name".equals(indexes.getString("COLUMN_NAME")), "index column should be normalized for DBeaver");
                    }
                }
            }
            require(hasNameIndex, "metadata should expose LiteDB indexes");

            try (ResultSet privileges = metaData.getTablePrivileges(null, null, "people")) {
                require(privileges.getMetaData().getColumnCount() == 7, "table privilege metadata should expose JDBC columns");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select $ from $cols")) {
                ResultSetMetaData resultMetaData = rows.getMetaData();
                while (rows.next()) {
                    for (int i = 1; i <= resultMetaData.getColumnCount(); i++) {
                        if (i > 1) {
                            System.out.print("\t");
                        }
                        System.out.print(resultMetaData.getColumnLabel(i) + "=" + rows.getString(i));
                    }
                    System.out.println();
                }
            }

            try (PreparedStatement statement = connection.prepareStatement("select name, age from people where name = ?")) {
                statement.setString(1, "Ada");
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "prepared query should return Ada");
                    require("Ada".equals(rows.getString("name")), "prepared query should bind string parameter");
                    require(rows.getInt("age") == 37, "typed getters should work");
                    require(Integer.valueOf(37).equals(rows.getObject("age", Integer.class)), "typed getObject should work");
                }
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select * from people")) {
                require(rows.next(), "traditional SELECT * should return document rows");
                require("Ada".equals(rows.getString("name")), "traditional SELECT * should expose document fields");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select top 1 * from \"people\"")) {
                require(rows.next(), "quoted identifier plus TOP should be accepted");
                require("people".equals(rows.getMetaData().getTableName(1)), "result metadata should expose source collection");
                require("Ada".equals(rows.getString("name")), "quoted SELECT * should expose document fields");
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select \"name\" from \"people\" limit 1, 1")) {
                require(rows.next(), "SQLite-style LIMIT offset,count should be accepted");
                require("Linus".equals(rows.getString("name")), "SQLite-style LIMIT should map to LiteDB OFFSET");
            }

            try (Connection secondConnection = DriverManager.getConnection(args[0], properties);
                 Statement statement = secondConnection.createStatement();
                 ResultSet rows = statement.executeQuery("select $ from people")) {
                require(rows.next(), "a second concurrent connection should read the same LiteDB file");
            }

            String prefix = "jdbc:litedb:";
            if (args[0].startsWith(prefix) && args[0].substring(prefix.length()).indexOf('=') < 0) {
                try (Connection aliasConnection = DriverManager.getConnection(
                        "jdbc:litedb:filename=" + args[0].substring(prefix.length()),
                        properties)) {
                    require(aliasConnection.isValid(1), "filename alias should connect");
                }
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
