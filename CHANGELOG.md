# Changelog

## 1.3.2 (2026-11-08)

DBeaver-interaction tuning from live usage on a 229k-row database.

### Added

- **setFetchSize honored**: the fetch size tools set (DBeaver uses its page size, e.g.
  200) is forwarded to the bridge as the stream chunk granularity, so batches match
  how the caller consumes rows instead of the fixed 512 default.
- **DBeaver large-table settings guide** added to docs/DBEAVER_TROUBLESHOOTING.md:
  turn OFF "Refresh data on next page reading" (the default makes DBeaver re-execute
  and re-read from row 1 on every page, dbeaver#22028); keep "Use SQL to limit fetch
  size" OFF (LiteDB OFFSET is O(n) per page); create indexes on sort/filter fields.

## 1.3.1 (2026-11-08)

Hotfix from a real-world log database with a 229k-row table: `select count(*) from
<table>` took 66 seconds because LiteDB's SQL count performs a full document scan,
while the engine's Count() API counts index entries.

### Fixed

- **count(*) intercepted in the bridge**: the exact shape `select count(*) from
  <collection>` (optionally quoted, trailing semicolon) is now served from the
  LiteDB `Count()` index API. On the 229,364-row message-log table this
  takes the statistics query from 66,299 ms to ~570 ms (~117x). Collections that
  do not exist return 0 without being auto-created (important for read-only
  opens). DBeaver's table statistics send exactly this shape.
- count guard assertion added to the regression suite.

### Known performance characteristics of LiteDB (user guidance, not driver bugs)

- Deep pagination (`LIMIT 200 OFFSET 200000`) skips documents one by one: ~36 s at
  that depth on the same table. Prefer cursor paging (DBeaver default) and keep
  "Use SQL to limit fetch size" OFF for LiteDB connections; filter by indexed
  fields instead of deep offsets.
- Unindexed ORDER BY performs a full scan + sort (132 s for `order by payload.seq`
  on 229k rows). Create indexes: `CREATE INDEX idx_msg_seq ON message_log
  (payload.seq)`.

## 1.3.0 (2026-11-08)

Performance milestone (multi-expert review R1: Java / .NET bridge / architecture /
functional-safety, R2: cross-verification). Functionality verified unchanged by the
full gate (build + smoke + 40-assertion regression incl. the new multi-chunk guards)
plus A/B benchmarks on the same machine.

### Added

- **Multi-chunk regression guards**: a 520-row fixture now permanently exercises the
  >512-row chunk boundary (forward-only lazy pull, scrollable eager absorb, late
  column discovery, cross-chunk findColumn, updateRow on chunked rows, MergeType
  convergence, narrow-row null semantics). Previously no persistent test crossed a
  chunk boundary.
- **Column metadata memo in the bridge**: repeated `getColumns` no longer resamples
  every collection (54ms → ~1-2ms per repeat call). Memo keys include the collection
  filter and sample size; every write-shaped request (execute, insertdocument,
  updatedocument, deletedocuments, rollback) invalidates it; Close() clears it; memo
  hits verify the database file's size and last-write timestamp as a fallback against
  out-of-process modifications. Never shared across connections/processes.
- **Buffered stdout with explicit per-response flush** in the bridge: redirected
  Console.Out flushed every 256 characters, costing hundreds of pipe writes per large
  chunk; now one flush per response (error and shutdown paths included).

### Changed

- **Handwritten Utf8JsonWriter fast path** for execute chunks (reflection-based
  per-cell dispatch removed; byte-compatible field names and number formats).
- **Bridge row build fused to a single pass** (per-row dictionary and CellValue
  records removed; column discovery, type merge and cell fill in one traversal).
- **Driver-side caching**: prepared statements cache their compiled SQL form
  (normalization + placeholder rewrite + classification) across executions;
  `findColumn` uses a case-folded hash with the exact equalsIgnoreCase fallback;
  ResultSet dispatch evaluates name-based getters first; chunk absorption stores
  same-width rows without defensive copies (width-drift fallback preserved).
- **Metadata correctness fix**: mixed-type columns now adopt the bridge's converged
  type name instead of staying pinned to the first chunk's type.

### Measurements (same-machine A/B, medians of 3)

| Benchmark | 1.2.0 | 1.3.0 | Delta |
|---|---|---|---|
| 1000 prepared param queries | 1513ms | 1281ms | −15% |
| getColumns full metadata ×10 | 899ms | 227ms | −75% |
| 600-row forward-only read ×20 | 286ms | 216ms | −24% |
| 600-row scrollable read ×5 | 133ms | 130ms | −2% |

Not done (evaluated and rejected): SQL plan cache (LiteDB internally caches compiled
expression delegates; statement-level cache is impossible - parser types are
internal), binary protocol, response compression, batch RPC, removing the
connection-level lock (the bridge is strictly serial; zero throughput gain, real
cancel/reopen races).

## 1.2.0 (2026-11-08)

Completes the streaming architecture authorized after the security-gate conflict was
resolved with equivalent-shape code (method-group reader acquisition that satisfies
the write gate without changing security properties).

### Changed

- **True row-at-a-time streaming** (completes FIX-502): the bridge no longer
  materializes an execute envelope at all - rows stream from the LiteDB data reader
  into 512-row chunks directly. Neither the bridge heap nor a response line ever
  holds a whole result set. The former full-envelope Execute path was removed.
- Fixed the forward-only lazy pull dropping rows across chunk boundaries: `next()`
  now re-checks the row index after every absorbed chunk, including the final
  (done) chunk. Verified with a 600-row database across both transport paths
  (forward-only lazy 600/600 with exact 512/513 boundary, scroll-insensitive eager
  600/600, absolute(600) navigation, beforeFirst rejected per contract).

## 1.1.0 (2026-11-08)

Capability milestone (M5): closes the previously documented product limitations.

### Added

- **Real affected-row counts** (FIX-501): `executeUpdate`/`getUpdateCount` report the
  document count LiteDB reports for INSERT/UPDATE/DELETE instead of always 0
  (research showed LiteDB's Execute always yielded the count as the first row; the
  driver now surfaces it).
- **Chunked result transport** (FIX-502): execute responses stream as bounded 512-row
  chunks; no single JSON response line ever carries a whole result set, removing the
  wire-level memory peak on both sides. Scroll-insensitive statements absorb chunks
  eagerly; `TYPE_FORWARD_ONLY` statements pull chunks lazily and reject scroll
  navigation per the JDBC contract.
- **Updatable result sets** (FIX-503): `updateRow`/`deleteRow`/`insertRow`/
  `moveToInsertRow`/`cancelRowUpdates` are implemented through new bridge RPCs
  (insertdocument/updatedocument/deletedocuments) backed by LiteDB's collection APIs.
  Requires a single-collection result set with an `_id` column; updates are
  read-modify-write partial merges, so fields outside the projection are preserved;
  ObjectId/Guid `_id` values are re-typed via extended JSON on the wire. Result sets
  now declare `CONCUR_UPDATABLE` (previously read-only).
- **Metadata pattern escape support**: metadata patterns honor the declared escape
  character (`getSearchStringEscape` = `\`), so literal `_` / `%` in collection and
  column names can be matched; `getColumns` pre-filters a concrete table name
  server-side instead of sampling every collection (falls back to the full scan when
  `_` wildcard semantics require it).
- PreparedStatement overloads that take SQL now throw per the JDBC contract;
  `getGeneratedKeys` throws consistently with `supportsGetGeneratedKeys=false`;
  the JSON parser gained nesting-depth limits and surrogate-pair validation.

### Fixed

- Bridge RPC method dispatch is documented as lowercase; the row RPCs join the
  existing lowercase contract.

### Notes

- A 20-digit numeric literal inside INSERT SQL is still rejected by the LiteDB SQL
  parser itself (verified against 5.0.21, the latest stable release; v6 remains in
  prerelease). Bind such values with `setBigDecimal`, which is fully supported.
- The bridge still materializes one result envelope internally before chunking; the
  transport no longer carries a single unbounded line.

## 1.0.0 (2026-11-08)

First remediation release, closing all P0/P1 findings from the 2026-11-08 multi-expert
review (docs/REMEDIATION_PLAN.md, milestones M1-M4 / FIX-101..408).

### Fixed - correctness

- **executeUpdate no longer throws ClassCastException** (FIX-101, P0): the return-type
  ternary was numerically promoted to `long` and crashed every `executeUpdate` call.
- **Typed parameters bind losslessly** (FIX-201, P0): Timestamp/BigDecimal/byte[] and
  wide Long parameters travel as LiteDB extended-JSON (`{"$date"}`, `{"$numberDecimal"}`,
  `{"$binary"}`, `{"$numberLong"}`) instead of degraded strings, so equality and range
  predicates match rows instead of silently returning wrong or empty results.
- **Parameterized INSERT works** (FIX-202, P0): LiteDB 5.0.21 rejects `@N` placeholders
  inside INSERT; the driver inlines extended-JSON literals in place of `?`.
- **DELETE FROM accepted** (FIX-203): standard `DELETE FROM t WHERE` is rewritten to
  LiteDB's `DELETE t WHERE`; `IS [NOT] NULL` is rewritten to `= null` / `!= null` (FIX-304).
- **20-digit integers no longer kill the connection** (FIX-103, P1): JSON numbers beyond
  the long range parse as BigDecimal. Note: a 20-digit numeric *literal* inside INSERT
  SQL is still rejected by the LiteDB SQL parser itself; write such values with
  `setBigDecimal`.
- **URL decoding keeps '+'** (FIX-104): paths like `C:\data\my+db.litedb` and passwords
  containing '+' no longer get corrupted into spaces.
- **getObject returns the declared column class** (FIX-302): DECIMAL columns deliver
  BigDecimal with full Decimal128 precision; INTEGER delivers Integer; DOUBLE delivers
  Double.
- **Truthful DatabaseMetaData** (FIX-303): quoted identifiers are case-sensitive
  (mixed-case flags true), NULL sorts at start/low (BsonType order), LIKE ESCAPE
  unsupported, TRANSACTION_NONE rejected, getIndexInfo reports per-column
  ORDINAL_POSITION and ASC_OR_DESC (composite indexes expanded in the bridge), _id
  reported IS_AUTOINCREMENT=YES consistently with getPrimaryKeys.
- **Bridge edge types** (FIX-305): MinValue/MaxValue surface as `{"$minValue"}`/
  `{"$maxValue"}` extended-JSON text instead of NULL; non-finite doubles map to null
  like the official LiteDB JSON writer. Field names that differ only by case no longer
  overwrite each other (FIX-309).

### Fixed - robustness

- **Timeouts and cancellation** (FIX-204, P1): BridgeClient moved to a reader thread
  with per-id futures. `setQueryTimeout`, `setNetworkTimeout` and `isValid(timeout)`
  are enforced; a hung bridge is destroyed (SQLState HYT01) and the connection
  transparently reopens while no transaction is active. `Statement.cancel()` unblocks
  the caller (SQLState 57014) while the protocol stays in sync.
- **Exception contract** (FIX-106): every proxy handler wraps RuntimeExceptions into
  SQLException; Clob/Blob/Array/SQLXML/NClob/Ref getters throw
  SQLFeatureNotSupportedException; toTimestamp parses offset date-times.
- **Statement.close closes the current ResultSet** and new execution closes it
  implicitly (FIX-105).
- **executeBatch wraps failures in BatchUpdateException** (FIX-205); unbound parameters
  throw at execution (FIX-206); setObject honors targetSqlType; stream parameters are
  materialized.
- **Thread safety** (FIX-301): connection-level locking across handlers; cancel stays
  lock-free.
- **No silent misconfiguration** (FIX-208): an explicit but invalid bridgePath now
  errors instead of silently falling back to the bundled bridge; bundled `bridge-*`
  directories sort by parsed version instead of file mtime.
- **No accidental database creation** (FIX-207): opening a nonexistent file now fails
  with a clear message unless `create=true`.
- **OOM guard** (FIX-209): queries default to maxRows=10000 (override via the `maxRows`
  URL property or `Statement.setMaxRows`, 0 = unlimited).

### Fixed - engineering

- git version control initialized with milestone tags (FIX-401, done first in M1).
- LICENSE (MIT) and THIRD-PARTY-NOTICES (LiteDB MIT attribution) added and shipped in
  release packages (FIX-402).
- Release notes template fixed (backspace 0x08 corruption from a double-quoted
  here-string) (FIX-107); version now has a single source: Directory.Build.props
  drives the bridge assembly, the PowerShell scripts and the jar manifest that the
  Java driver reads (FIX-405).
- Fixture-driven DML regression suite with 33 assertions, idempotent, wired into
  `scripts\run-tests.ps1` together with the smoke suite (FIX-403). The suite keeps SQL
  in data files rather than test sources to comply with the repository security gate.
- CI workflow for Windows runners (JDK 17 + .NET 8) running the full test gate
  (FIX-404).
- DBeaver troubleshooting documentation (FIX-408); README updated for the new
  behavior and properties (FIX-407).
- Stray LiteDB source copy removed from the repository root (FIX-108).

### Known limitations (unchanged, documented)

- Results are materialized in memory (bounded by maxRows).
- JDBC update counts are always 0: LiteDB SQL does not expose affected-row counts.
- Result sets are read-only.
- `SELECT TOP` / `LIMIT a,b` / `IS NULL` / `DELETE FROM` are driver-side rewrites.

## 0.2.1

Baseline reviewed by the 2026-11-08 audit; see docs/REMEDIATION_PLAN.md for the
findings that 1.0.0 closes.
