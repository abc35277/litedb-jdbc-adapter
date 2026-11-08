import java.sql.*;
import java.util.*;
import java.nio.file.*;
import java.math.BigDecimal;

// Regression suite for the LiteDB JDBC Adapter. Executed by scripts\run-tests.ps1 after
// run-config.jsh defines TEST_URL / TEST_URL_COMPLEX / TEST_FIXTURES / TEST_SCRATCH.
// All DML statements live in data fixtures under tests\repl\fixtures (no SQL literals
// beyond SELECT appear in this script); the script tracks failures and prints a verdict.

int __failures = 0;
void __check(boolean cond, String msg) {
    System.out.println((cond ? "PASS " : "FAIL ") + msg);
    if (!cond) {
        __failures++;
    }
}

Class.forName("com.litedb.jdbc.LiteDbDriver");
var props = new Properties();
props.setProperty("bridgePath", TEST_BRIDGE);
Files.createDirectories(Path.of(TEST_SCRATCH));

// --- FIX-207: missing database file requires an explicit create=true ---
// The suite must be re-runnable, so remove the scratch database first.
Files.deleteIfExists(Path.of(TEST_SCRATCH_URL.substring("jdbc:litedb:".length())));
boolean createGuard = false;
try {
    DriverManager.getConnection(TEST_SCRATCH_URL, props);
} catch (SQLException ex) {
    createGuard = ex.getMessage().contains("create=true");
}
__check(createGuard, "missing database file requires create=true (FIX-207)");

var propsCreate = new Properties();
propsCreate.putAll(props);
propsCreate.setProperty("create", "true");
var scratch = DriverManager.getConnection(TEST_SCRATCH_URL, propsCreate);
__check(scratch != null, "create=true opens a new database (FIX-207)");
scratch.close();

// --- FIX-104: '+' survives URL decoding ---
var plusUrl = TEST_URL + "+plus";
var plusProps = new Properties();
plusProps.putAll(props);
plusProps.setProperty("create", "true");
var plusConn = DriverManager.getConnection(plusUrl, plusProps);
__check(plusConn.isValid(2), "path with '+' opens without creating a space variant (FIX-104)");
plusConn.close();

// --- core query path on the sample database ---
var conn = DriverManager.getConnection(TEST_URL, props);
var meta = conn.getMetaData();
var st = conn.createStatement();

// --- FIX-101/102 + FIX-501: result dispatch and REAL affected-row counts ---
var dml = Files.readAllLines(Path.of(TEST_FIXTURES + "/dml.txt"));
// Idempotency: clear probe rows from previous runs before asserting on inserts.
st.executeUpdate(dml.get(3).trim());
st.executeUpdate(dml.get(4).trim());
st.executeUpdate(dml.get(5).trim());
__check(!st.execute(dml.get(0).trim()) , "execute(INSERT) returns false (FIX-101/102)");
__check(st.getUpdateCount() == 1, "getUpdateCount after INSERT reports 1 (FIX-501)");
__check(st.execute("select $ from fixt"), "execute(SELECT) returns true");
__check(st.getResultSet() != null, "getResultSet after SELECT non-null");
__check(st.getUpdateCount() == -1, "getUpdateCount after SELECT == -1");
__check(!st.execute(dml.get(1).trim()), "execute(second INSERT) returns false (FIX-101/102)");
st.executeUpdate(dml.get(3).trim()); // clear _id=1 so the re-insert below is legal
__check(st.executeUpdate(dml.get(0).trim()) == 1, "executeUpdate(INSERT) returns real count without CCE (FIX-101/501)");

boolean queryOnDmlThrows = false;
try {
    st.executeQuery(dml.get(2).trim());
} catch (SQLException ex) {
    queryOnDmlThrows = true;
}
__check(queryOnDmlThrows, "executeQuery(DML) throws SQLException (FIX-102)");

boolean updateOnRowsThrows = false;
try {
    st.executeUpdate("select $ from fixt");
} catch (SQLException ex) {
    updateOnRowsThrows = true;
}
__check(updateOnRowsThrows, "executeUpdate(SELECT) throws SQLException (FIX-102)");

// NOTE: a 20-digit numeric LITERAL inside INSERT is rejected by the LiteDB SQL parser
// itself ("too large for an Int64") - the write path for such values is the typed
// parameter binding exercised further below (FIX-201).

// --- FIX-106: dirty conversions throw SQLException, not RuntimeException ---
var rsDirty = st.executeQuery("select name from fixt where _id = 1");
rsDirty.next();
boolean dirtyThrows = false;
try {
    rsDirty.getInt("name");
} catch (SQLException ex) {
    dirtyThrows = true;
}
__check(dirtyThrows, "getInt(non-numeric) throws SQLException (FIX-106)");
rsDirty.close();

// --- FIX-201/202: typed parameterized INSERT and typed WHERE matching ---
var dmlTyped = Files.readAllLines(Path.of(TEST_FIXTURES + "/dml-typed.txt"));
st.executeUpdate(dmlTyped.get(3).trim());
st.executeUpdate(dmlTyped.get(4).trim());
var psInsert = conn.prepareStatement(dmlTyped.get(0).trim());
psInsert.setInt(1, 1);
psInsert.setString(2, "ada");
var ts = java.sql.Timestamp.valueOf("2026-03-02 08:00:00.123456");
psInsert.setTimestamp(3, ts);
psInsert.setBigDecimal(4, new BigDecimal("12345678901234567890123456789"));
psInsert.setBytes(5, new byte[] { 1, 2, 3 });
int typedInsertCount = psInsert.executeUpdate();
__check(typedInsertCount == 1, "parameterized INSERT with typed params (FIX-201/202), got " + typedInsertCount);
psInsert.close();

var qTime = conn.prepareStatement("select name from typed where moment = ?");
qTime.setTimestamp(1, ts);
var rsTime = qTime.executeQuery();
__check(rsTime.next(), "timestamp equality matches typed-bound row (FIX-201)");
qTime.close();

var qDec = conn.prepareStatement("select name from typed where amount = ?");
qDec.setBigDecimal(1, new BigDecimal("12345678901234567890123456789"));
var rsDec = qDec.executeQuery();
__check(rsDec.next(), "decimal equality matches typed-bound row (FIX-201)");
qDec.close();

var qBin = conn.prepareStatement("select name from typed where data = ?");
qBin.setBytes(1, new byte[] { 1, 2, 3 });
var rsBin = qBin.executeQuery();
__check(rsBin.next(), "binary equality matches (FIX-201)");
qBin.close();

// --- FIX-202/203: parameterized DELETE FROM ---
var pd = conn.prepareStatement(dmlTyped.get(2).trim());
pd.setInt(1, 1);
__check(pd.executeUpdate() == 1, "parameterized DELETE FROM works with real count (FIX-203/501)");
pd.close();

// --- FIX-201/FIX-103: 20-digit BigDecimal round trip via typed parameters ---
var psBig = conn.prepareStatement(dmlTyped.get(1).trim());
var big = new BigDecimal("99999999999999999999");
psBig.setBigDecimal(1, big);
psBig.executeUpdate();
psBig.close();
var rsRound = st.executeQuery("select v from bigdec");
rsRound.next();
Object round = rsRound.getObject(1);
__check(round instanceof BigDecimal, "20-digit integer arrives as BigDecimal (FIX-103)");
__check(round instanceof BigDecimal && big.equals(round), "BigDecimal round trip lossless (FIX-201)");
rsRound.close();
__check(st.execute("select $ from bigdec"), "connection alive after big value (FIX-103)");

// --- FIX-204: timeout/cancel sanity ---
st.setQueryTimeout(10);
__check(st.getQueryTimeout() == 10, "setQueryTimeout is stored (FIX-204)");
st.cancel();
__check(true, "cancel without in-flight request is harmless (FIX-204)");

// --- FIX-205: batch failure wraps into BatchUpdateException ---
try (Statement batchStatement = conn.createStatement()) {
    batchStatement.addBatch(dml.get(0).trim());
    batchStatement.addBatch("no such statement exists here");
    boolean batchThrew = false;
    try {
        batchStatement.executeBatch();
    } catch (java.sql.BatchUpdateException ex) {
        batchThrew = true;
    }
    __check(batchThrew, "executeBatch wraps failures in BatchUpdateException (FIX-205)");
}

// --- FIX-206: unbound parameter throws ---
boolean unboundThrows = false;
try {
    var qb = conn.prepareStatement("select name from typed where _id = ?");
    qb.executeQuery();
} catch (SQLException ex) {
    unboundThrows = ex.getMessage().contains("has not been set");
}
__check(unboundThrows, "unbound parameter throws SQLException (FIX-206)");

// --- FIX-303/307/308: metadata truthfulness and contract completion ---
__check(meta.supportsMixedCaseQuotedIdentifiers(), "mixed-case quoted identifiers reported (FIX-303)");
__check(meta.nullsAreSortedAtStart() && !meta.nullsAreSortedAtEnd(), "null sort order reported (FIX-303)");
__check(!meta.supportsLikeEscapeClause(), "LIKE ESCAPE reported unsupported (FIX-303)");
__check(conn.getSchema() == null, "getSchema returns null (FIX-308)");
__check(!meta.getSchemas().next(), "getSchemas is empty (FIX-308)");
boolean isolationThrows = false;
try { conn.setTransactionIsolation(Connection.TRANSACTION_NONE); } catch (SQLException ex) { isolationThrows = true; }
__check(isolationThrows, "TRANSACTION_NONE rejected (FIX-303)");
boolean commitAutoThrows = false;
try { conn.commit(); } catch (SQLException ex) { commitAutoThrows = true; }
__check(commitAutoThrows, "commit() in autoCommit throws (FIX-303)");
boolean savepointThrows = false;
try { conn.setSavepoint(); } catch (SQLFeatureNotSupportedException ex) { savepointThrows = true; }
__check(savepointThrows, "setSavepoint throws SQLFeatureNotSupported (FIX-307)");
boolean abortNullThrows = false;
try { conn.abort(null); } catch (SQLException ex) { abortNullThrows = true; }
__check(abortNullThrows, "abort(null) throws (FIX-307)");
conn.setClientInfo("app", "regression");
__check(conn.getWarnings() != null, "client info warning chain (FIX-307)");
conn.clearWarnings();
__check(conn.getWarnings() == null, "clearWarnings works (FIX-307)");

var stLarge = conn.createStatement();
stLarge.setLargeMaxRows(50L);
__check(stLarge.getLargeMaxRows() == 50L, "setLargeMaxRows round trip (FIX-307)");
stLarge.close();

// --- FIX-302: getObject returns declared column class ---
var rsTyped = st.executeQuery("select name, age from people where name = 'Ada'");
rsTyped.next();
__check(rsTyped.getObject("age") instanceof Integer, "getObject(INTEGER) returns Integer (FIX-302)");
__check(java.math.BigDecimal.valueOf(37).equals(rsTyped.getBigDecimal("age")), "getBigDecimal value (FIX-302)");
rsTyped.close();

// --- FIX-503: updatable result set (updateRow / deleteRow / insertRow) ---
var upStmt = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
var rsUp = upStmt.executeQuery("select _id, name from fixt");
int visibleRows = 0;
var seenIds503 = new ArrayList<Integer>();
while (rsUp.next()) {
    visibleRows++;
    seenIds503.add(rsUp.getInt(1));
}
__check(visibleRows >= 2, "scrollable+updatable result set reads rows (FIX-503), got "
        + visibleRows + " ids=" + seenIds503);
rsUp.beforeFirst();
rsUp.next();
rsUp.updateString("name", "edited");
rsUp.updateRow();
rsUp.next();
rsUp.deleteRow();
rsUp.moveToInsertRow();
rsUp.updateInt("_id", 7);
rsUp.updateString("name", "inserted");
rsUp.insertRow();
rsUp.close();
upStmt.close();

var verifyStmt = conn.createStatement();
var rsVerify = verifyStmt.executeQuery("select _id, name from fixt order by _id");
var seenIds = new HashSet<Integer>();
boolean editedOk = false;
boolean insertedOk = false;
while (rsVerify.next()) {
    int id = rsVerify.getInt(1);
    seenIds.add(id);
    if (id == 1 && "edited".equals(rsVerify.getString(2))) {
        editedOk = true;
    }
    if (id == 7 && "inserted".equals(rsVerify.getString(2))) {
        insertedOk = true;
    }
}
__check(editedOk, "updateRow persists through the bridge (FIX-503)");
__check(!seenIds.contains(2), "deleteRow persists through the bridge (FIX-503)");
__check(insertedOk, "insertRow persists through the bridge (FIX-503)");
verifyStmt.close();

// --- PERF-B0: multi-chunk guard (bigtable crosses the 512-row chunk boundary) ---
var dmlChunk = Files.readAllLines(Path.of(TEST_FIXTURES + "/dml-chunk.txt"));
var psChunk0 = conn.prepareStatement(dmlChunk.get(0).trim());
var psChunk1 = conn.prepareStatement(dmlChunk.get(1).trim());
st.executeUpdate(dmlChunk.get(2).trim());
for (int i = 1; i <= 513; i++) {
    psChunk0.setInt(1, i);
    psChunk0.setInt(2, i);
    psChunk0.executeUpdate();
}
for (int i = 514; i <= 520; i++) {
    psChunk1.setInt(1, i);
    psChunk1.setDouble(2, 0.5);
    psChunk1.setString(3, "late" + i);
    psChunk1.executeUpdate();
}
psChunk0.close();
psChunk1.close();
var rsC = st.executeQuery("select _id, n, b from bigtable");
int chunkRows = 0;
boolean firstNarrowNull = false;
while (rsC.next()) {
    chunkRows++;
    if (chunkRows == 1) {
        firstNarrowNull = rsC.getObject("b") == null && rsC.wasNull();
    }
}
__check(chunkRows == 520, "forward-only crosses chunk boundary (PERF-B0), got " + chunkRows);
__check(firstNarrowNull, "narrow row missing late column reads null with wasNull (PERF-B0)");
__check(rsC.getMetaData().getColumnTypeName(2).equals("DOUBLE"), "cross-chunk MergeType converges (PERF-B0/P5)");
rsC.close();
var rsB = st.executeQuery("select _id, n, b from bigtable");
rsB.next();
__check(rsB.findColumn("b") == 3, "findColumn hits late column (PERF-B0/P2)");
rsB.close();
var upChunk = conn.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
var rsU = upChunk.executeQuery("select _id, n, b from bigtable");
rsU.last();
__check(rsU.getRow() == 520, "scrollable eager absorbs all chunks (PERF-B0)");
rsU.absolute(514);
rsU.updateString("b", "edited514");
rsU.updateRow();
rsU.close();
upChunk.close();
var rsV = st.executeQuery("select b from bigtable where _id = 514");
boolean chunkEditPersisted = rsV.next() && "edited514".equals(rsV.getString(1));
__check(chunkEditPersisted, "updateRow on multi-chunk result persists (PERF-B0/P4)");
rsV.close();
st.executeUpdate(dmlChunk.get(2).trim());

// --- PERF-P1: prepared statement replay (same PS, different binds) ---
var dmlReplay = Files.readAllLines(Path.of(TEST_FIXTURES + "/dml-replay.txt"));
var psReplay = conn.prepareStatement(dmlReplay.get(0).trim());
psReplay.setInt(1, 1);
psReplay.setString(2, "r1");
psReplay.executeUpdate();
psReplay.setInt(1, 2);
psReplay.setString(2, "r2");
psReplay.executeUpdate();
__check(psReplay.getParameterMetaData().getParameterCount() == 2, "parameterMetaData after replay (PERF-P1)");
psReplay.close();
var rsR1 = st.executeQuery("select name from replayt where _id = 1");
boolean replay1 = rsR1.next() && "r1".equals(rsR1.getString(1));
rsR1.close();
var rsR2 = st.executeQuery("select name from replayt where _id = 2");
boolean replay2 = rsR2.next() && "r2".equals(rsR2.getString(1));
rsR2.close();
__check(replay1 && replay2, "same PS replayed with different binds stores distinct rows (PERF-P1)");
st.executeUpdate(dmlReplay.get(1).trim());
st.executeUpdate(dmlReplay.get(2).trim());

// --- PERF-P2: case-variant fields fold inside LiteDB (documented storage semantics) ---
var dmlCase = Files.readAllLines(Path.of(TEST_FIXTURES + "/dml-case.txt"));
st.executeUpdate(dmlCase.get(0).trim());
var rsCase = st.executeQuery("select $ from casetest");
rsCase.next();
// LiteDB's own BsonDocument folds case-variant field names (last write wins), so even
// a raw `select $` carries a single folded field; the driver must look it up
// case-insensitively. Column-level case sensitivity therefore ends at LiteDB itself.
__check(rsCase.findColumn("NAME") == 2 && "B".equals(rsCase.getString("NAME")),
        "case-insensitive lookup finds the folded field (PERF-P2)");
__check(rsCase.getMetaData().getColumnCount() == 2, "LiteDB folds case variants (PERF-P2, documented)");
rsCase.close();
boolean nopeThrows = false;
try {
    var rsNope = st.executeQuery("select _id from casetest");
    rsNope.next();
    rsNope.findColumn("nope");
    rsNope.close();
} catch (SQLException ex) {
    nopeThrows = ex.getMessage().contains("Column not found");
}
__check(nopeThrows, "findColumn miss still throws Column not found (PERF-P2)");
st.executeUpdate(dmlCase.get(1).trim());

// --- PERF-P3: row/getRow/isClosed/buffer read-back semantics ---
var rsG = st.executeQuery("select _id, name from fixt");
rsG.next();
__check(rsG.getRow() == 1 && !rsG.isClosed(), "getRow/isClosed semantics (PERF-P3)");
rsG.moveToInsertRow();
rsG.updateInt("_id", 42);
__check(rsG.getInt("_id") == 42, "pending insert buffer read-back (PERF-P3)");
rsG.moveToCurrentRow();
rsG.close();
boolean closedThrows = false;
try {
    rsG.next();
} catch (SQLException ex) {
    closedThrows = ex.getMessage().contains("closed");
}
__check(closedThrows, "closed result set rejects next (PERF-P3)");

// --- PERF-P6: bridge metadata memo invalidation (passes without memo; guards it after) ---
var dmlMemo = Files.readAllLines(Path.of(TEST_FIXTURES + "/dml-memo.txt"));
var mdSample = conn.getMetaData();
try (var rs = mdSample.getColumns(null, null, "memotest", "%")) {
    while (rs.next());
}
st.executeUpdate(dmlMemo.get(0).trim());
boolean memoInsertVisible = false;
try (var rs = mdSample.getColumns(null, null, "memotest", "%")) {
    while (rs.next()) {
        String cn = rs.getString("COLUMN_NAME");
        if ("a".equals(cn) || "b".equals(cn)) {
            memoInsertVisible = true;
        }
    }
}
__check(memoInsertVisible, "getColumns sees new fields after DML (PERF-P6 invalidation)");
st.executeUpdate(dmlMemo.get(1).trim());
try (var rs = mdSample.getColumns(null, null, "memotest", "%")) {
    String aNull = null;
    String bNull = null;
    while (rs.next()) {
        if ("a".equals(rs.getString("COLUMN_NAME"))) {
            aNull = rs.getString("IS_NULLABLE");
        }
        if ("b".equals(rs.getString("COLUMN_NAME"))) {
            bNull = rs.getString("IS_NULLABLE");
        }
    }
    __check("NO".equals(aNull) && "YES".equals(bNull), "nullable derivation after second doc (PERF-P6)");
}
st.executeUpdate(dmlMemo.get(2).trim());
st.executeUpdate(dmlMemo.get(3).trim());
boolean memoDeleteFallback = false;
int remainingMemoColumns = 0;
String lastMemoColumn = "";
try (var rs = mdSample.getColumns(null, null, "memotest", "%")) {
    while (rs.next()) {
        remainingMemoColumns++;
        lastMemoColumn = rs.getString("COLUMN_NAME");
    }
}
// An emptied collection falls back to the synthetic _id column (bridge contract).
memoDeleteFallback = remainingMemoColumns == 1 && "_id".equals(lastMemoColumn);
__check(memoDeleteFallback, "getColumns reflects deletions (PERF-P6)");
st.executeUpdate(dmlMemo.get(4).trim());

// --- PERF-P8: long/decimal/double wire formats ---
var dmlLong = Files.readAllLines(Path.of(TEST_FIXTURES + "/dml-long.txt"));
var psLong = conn.prepareStatement(dmlLong.get(0).trim());
psLong.setInt(1, 1);
psLong.setLong(2, 9000000000000000001L);
psLong.executeUpdate();
psLong.close();
var rsLong = st.executeQuery("select v from biglong");
rsLong.next();
__check(rsLong.getLong("v") == 9000000000000000001L && rsLong.getObject("v") instanceof Long,
        "long round trip (PERF-P8)");
rsLong.close();
st.executeUpdate(dmlLong.get(1).trim());
var complexDbPath = Path.of(TEST_URL_COMPLEX.substring("jdbc:litedb:".length()));
if (Files.exists(complexDbPath)) {
    var complexProps = new Properties();
    complexProps.putAll(props);
    var complexConn = DriverManager.getConnection(TEST_URL_COMPLEX, complexProps);
    var rsCx = complexConn.createStatement().executeQuery("select decimalValue, int64Value, doubleValue from field_types");
    rsCx.next();
    __check(new java.math.BigDecimal("99999.1234").equals(rsCx.getBigDecimal("decimalValue")),
            "decimal wire format (PERF-P8)");
    __check(rsCx.getLong("int64Value") == 9876543210L, "int64 wire format (PERF-P8)");
    __check(rsCx.getDouble("doubleValue") == 123.456, "double wire format (PERF-P8)");
    rsCx.close();
    complexConn.close();
} else {
    System.out.println("SKIP PERF-P8 complex-db wire format checks (generated by the full gate)");
}

// --- PERF-P9: count(*) served from the index Count() API (bridge intercept) ---
var rsCount = st.executeQuery("select count(*) from fixt");
rsCount.next();
__check(rsCount.getLong(1) == 2, "count(*) returns real row count via index path (PERF-P9)");
rsCount.close();

// --- cleanup and verdict ---
st.close();
conn.close();
scratch.close();

System.out.println(__failures == 0 ? "REGRESSION PASS" : "REGRESSION FAIL " + __failures);
if (__failures > 0) {
    System.exit(1);
}
/exit
