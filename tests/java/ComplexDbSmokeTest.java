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

public final class ComplexDbSmokeTest {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ComplexDbSmokeTest <jdbc-url> [bridge-path] [password]");
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
            require(connection.isValid(1), "connection should validate");
            DatabaseMetaData metaData = connection.getMetaData();

            Set<String> tables = new HashSet<>();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[] { "TABLE" })) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME"));
                }
            }
            require(tables.contains("customers"), "customers collection should exist");
            require(tables.contains("orders"), "orders collection should exist");
            require(tables.contains("field_types"), "field_types collection should exist");
            require(tables.contains("sensor_readings"), "sensor_readings collection should exist");

            Set<String> fieldTypeColumns = new HashSet<>();
            try (ResultSet columns = metaData.getColumns(null, null, "field_types", "%")) {
                while (columns.next()) {
                    fieldTypeColumns.add(columns.getString("COLUMN_NAME"));
                }
            }
            require(fieldTypeColumns.contains("decimalValue"), "decimal field should be inferred");
            require(fieldTypeColumns.contains("documentValue"), "document field should be inferred");
            require(fieldTypeColumns.contains("unicodeValue"), "unicode field should be inferred");

            boolean skuIndex = false;
            try (ResultSet indexes = metaData.getIndexInfo(null, null, "products", false, false)) {
                while (indexes.next()) {
                    if ("ux_product_sku".equals(indexes.getString("INDEX_NAME"))) {
                        skuIndex = true;
                        require("sku".equals(indexes.getString("COLUMN_NAME")), "product index column should be normalized");
                    }
                }
            }
            require(skuIndex, "product sku index should exist");

            require(countRows(connection, "select $ from customers") == 12, "customers row count should match");
            require(countRows(connection, "select * from customers") == 12, "traditional SELECT * should map to LiteDB documents");
            require(countRows(connection, "select * from \"customers\" limit 0, 3") == 3, "quoted identifiers and SQLite LIMIT should work");
            require(countRows(connection, "select $ from orders") == 48, "orders row count should match");
            require(countRows(connection, "select $ from audit_logs") == 160, "audit log row count should match");

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select top 2 * from \"customers\" order by code")) {
                require(rows.next(), "TOP should be rewritten to LiteDB LIMIT");
                require("customers".equals(rows.getMetaData().getTableName(1)), "result metadata should expose source collection");
                require("CUST-1001".equals(rows.getString("code")), "TOP query should preserve ordering");
            }

            try (PreparedStatement statement = connection.prepareStatement("select code, name, address, contacts from customers where code = ?")) {
                statement.setString(1, "CUST-1001");
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "prepared query should return CUST-1001");
                    require("CUST-1001".equals(rows.getString("code")), "prepared parameter should bind");
                    require(rows.getString("address").contains("Hangzhou"), "nested document should be returned as JSON text");
                    require(rows.getString("contacts").contains("buyer"), "nested array should be returned as JSON text");
                }
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("select $ from field_types")) {
                ResultSetMetaData resultMeta = rows.getMetaData();
                require(resultMeta.getColumnCount() >= 8, "field_types should expose many columns");
                require(rows.next(), "field_types should have rows");
            }

            System.out.println("Complex DB OK: " + args[0]);
        }
    }

    private static int countRows(Connection connection, String sql) throws Exception {
        int count = 0;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                count++;
            }
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
