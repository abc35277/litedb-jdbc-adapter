# LiteDB JDBC Adapter

This project lets JDBC tools such as DBeaver query LiteDB files.

## Version compatibility

- Driver jar: Java 11+; bridge: .NET 8 runtime; LiteDB: 5.0.21 (pinned).
- JDBC 4.2 feature subset (non-compliant driver, `jdbcCompliant()` returns false).
- Distribution form: zip packages under `release/` (GitHub Releases when hosted);
  Maven Central publication is intentionally not part of 1.0.0.

## Testing

Full gate (build + smoke + DML regression suite):

```powershell
.\scripts\run-tests.ps1
```

Architecture:

- `litedb-jdbc-adapter.jar` is a small Java JDBC driver with no third-party Java dependencies.
- `LiteDbJdbc.Bridge` is a .NET helper process that uses the official `LiteDB` package.
- The JDBC driver starts the bridge over stdin/stdout and exposes LiteDB SQL results as JDBC result sets.

## Build

Requirements:

- .NET SDK 8 or newer
- JDK 11 or newer. This workstation has one at `E:\dev\env\jdk-26.0.1`.

Build everything:

```powershell
.\build.ps1 -JavaHome E:\dev\env\jdk-26.0.1
```

Outputs:

- `dist\litedb-jdbc-adapter.jar`
- `dist\litedb-jdbc-adapter-<version>.jar`
- `dist\bridge\LiteDbJdbc.Bridge.dll`
- `dist\bridge-<version>\LiteDbJdbc.Bridge.dll`
- `dist\bridge\LiteDbJdbc.Bridge.exe`
- `dist\dbeaver\drivers.xml`
- `dist\litedb-jdbc-adapter-dbeaver.zip`

If DBeaver is running, Windows may lock the stable `dist\litedb-jdbc-adapter.jar` or `dist\bridge`. The build still writes versioned jar/bridge outputs, and the install script prefers the latest versioned outputs so upgrades can continue without killing DBeaver.

Run a local smoke test:

```powershell
.\scripts\smoke.ps1 -JavaHome E:\dev\env\jdk-26.0.1
```

Generate larger test databases for DBeaver/manual testing:

```powershell
.\scripts\generate-complex-db.ps1
```

Outputs:

- `dist\testdb\ComplexTest.db`
- `dist\testdb\ComplexTest-encrypted.db` with password `Complex@123456`

Install/update the driver entry in the current DBeaver workspace:

```powershell
.\scripts\install-dbeaver-driver.ps1
```

Create a versioned local release package:

```powershell
.\scripts\release.ps1 -Version 0.2.1 -JavaHome E:\dev\env\jdk-26.0.1
```

## DBeaver Setup

Manual setup:

1. Open `Database > Driver Manager > New`.
2. Driver name: `LiteDB`.
3. Driver type: `Generic`.
4. Add file: `dist\litedb-jdbc-adapter.jar`.
5. Class name: `com.litedb.jdbc.LiteDbDriver`.
6. URL template: `jdbc:litedb:{file}`.
7. Create a connection with a URL such as:

```text
jdbc:litedb:E:\data\app.litedb
```

The driver auto-discovers the bridge when a `bridge` directory sits next to the jar. If DBeaver copies the jar elsewhere, add a driver property:

```text
bridgePath=E:\AI\LiteDB JDBC Adapter\dist\bridge
```

Portable package setup:

1. Extract `dist\litedb-jdbc-adapter-dbeaver.zip`.
2. In DBeaver, create a Generic driver and add the extracted `litedb-jdbc-adapter.jar`.
3. Set driver property `bridgePath` to the extracted `bridge` directory.
4. `dist\dbeaver\drivers.xml` is included as a ready reference for the driver id, class, URL, and DBeaver generic-driver parameters.

This workstation also has DBeaver running from `D:\app\dbeaver\dbeaver.exe`; `scripts\install-dbeaver-driver.ps1` adds a `LiteDB` generic driver entry to `%APPDATA%\DBeaverData\workspace6\.metadata\.config\drivers.xml`. Restart DBeaver if the driver does not appear immediately.

DBeaver may append query text to URL templates. This driver supports URLs like:

```text
jdbc:litedb:E:\data\app.litedb?bridgePath=E:\AI\LiteDB JDBC Adapter\dist\bridge
```

The semicolon URL format is often the most predictable on Windows.

Optional URL/property values:

- `password=<password>` for encrypted LiteDB files.
- Aliases such as `filename=`, `pwd=`, `readonly=`, and `connectionMode=` are accepted for compatibility with different JDBC tools.
- In DBeaver, enter encrypted-database passwords in the connection password field, or set the JDBC driver property `password`.
- `readOnly=true` to open the file read-only.
- `connection=shared` (default) to let DBeaver test and normal connections overlap without locking the LiteDB file.
- `connection=direct` to force LiteDB direct mode when you know only one connection will open the file.
- `columnSampleSize=200` to control schema inference.
- `create=true` to allow the bridge to create a new database file. By default a nonexistent file is an error, so typos cannot silently produce an empty database.
- `maxRows=10000` default row limit per query (guard against unbounded in-memory results); `Statement.setMaxRows`/`setLargeMaxRows` override it per statement, `0` = unlimited.
- `connectTimeout=<seconds>` to bound how long opening the database may take (0 = wait forever, the default).

Example semicolon URL:

```text
jdbc:litedb:file=E:\data\app.litedb;readOnly=true;bridgePath=E:\AI\LiteDB JDBC Adapter\dist\bridge
```

## Query Examples

LiteDB uses its own SQL dialect. Useful starting points:

```sql
select * from people
select $ from people
select name, age from people where age >= 18
select $ from $cols
select $ from $indexes
explain select $ from people where name = 'Ada'
select * from customers
select $ from customers
select code, name, address, contacts from customers where code = 'CUST-1001'
select $ from orders where status = 'delivered'
select $ from field_types
```

The driver rewrites the common `select * from collection` form to LiteDB's document-row form `select $ from collection`. Explicit column projections such as `select code, name from customers` are passed through as-is. Nested documents and arrays are returned as JSON strings.

For DBeaver and SQLite-style habits, the driver also normalizes simple quoted identifiers and common paging syntax before sending SQL to LiteDB:

```sql
select * from "customers"
select "code", "name" from [customers]
select top 10 * from customers
select * from customers limit 20, 10
```

These become LiteDB-compatible unquoted identifiers, `LIMIT`, and `LIMIT ... OFFSET ...` forms. JDBC result-set metadata also reports the source collection when LiteDB returns rows from a single collection, which helps tools treat results more like ordinary table results.

## Current Scope

Implemented JDBC surface:

- Driver loading via `java.sql.Driver` and `META-INF/services/java.sql.Driver`.
- Connection, statement, prepared statement, result set, result-set metadata, and database metadata through JDBC proxies.
- DBeaver metadata calls for tables, columns, primary keys, indexes, row identifiers, foreign-key placeholders, privileges, catalogs, schemas, table types, and type info.
- Read queries and LiteDB SQL execution through the .NET bridge.
- Typed parameter binding: Timestamp/BigDecimal/byte[] parameters bind as LiteDB extended-JSON, so date/decimal/binary predicates match rows.
- Parameterized INSERT (the driver inlines extended-JSON literals; LiteDB's INSERT grammar rejects `@N` placeholders).
- Standard-SQL rewrites: `DELETE FROM t`, `IS [NOT] NULL`, `TOP n`, `LIMIT a,b`, quoted identifiers.
- Query timeout, `setNetworkTimeout`, `Statement.cancel()`, and `isValid(timeout)` are enforced against the bridge.
- Read-only transactions via LiteDB begin/commit/rollback.
- Real affected-row counts: `executeUpdate`/`getUpdateCount` report the document count for INSERT/UPDATE/DELETE.
- Updatable result sets: `updateRow`/`deleteRow`/`insertRow` work on single-collection result sets with an `_id` column (updates are partial merges; unselected fields are preserved). DBeaver's inline grid editing can use this.
- Chunked result transport: results stream in bounded 512-row blocks (no single unbounded response line); `TYPE_FORWARD_ONLY` statements pull chunks lazily, scrollable statements materialize as JDBC requires.

Limitations:

- Scrollable (`TYPE_SCROLL_INSENSITIVE`) result sets are materialized in memory before they are returned to JDBC (bounded by `maxRows`, default 10000); forward-only result sets stay lazy.
- A 20-digit numeric literal inside INSERT SQL is rejected by the LiteDB parser itself; bind such values with `setBigDecimal` instead.
- Result-set updates require a single-collection query with `_id` selected; multi-collection or projected-without-_id results stay read-only.
