# LiteDB JDBC Adapter 整改修复计划（REMEDIATION PLAN）

| 项目 | 内容 |
|---|---|
| 文档版本 | v1.0 |
| 编制日期 | 2026-11-08 |
| 依据 | 2026-11-08 五专家审查（4 维度审查 + 1 交叉复核，含 shipped jar 端到端探针实测） |
| 基线版本 | v0.2.1（`dist\litedb-jdbc-adapter-0.2.1.jar` + `dist\bridge-0.2.1`） |
| 范围 | `src/driver/com/litedb/jdbc/`（5 个 Java 类）、`src/bridge/LiteDbJdbc.Bridge/Program.cs`、`build.ps1`、`scripts/*.ps1`、`tests/java/`、发布与工程化 |

---

## 1. 审查结论摘要（任务来源）

多专家审查 + 交叉复核确认的完成度现状：

| 维度 | 完成度 | 关键结论 |
|---|---|---|
| 功能（读路径） | ~80% | 连接/元数据/查询/加密/SQL 重写端到端可用；DatabaseMetaData 列序与 JDBC 规范逐列一致 |
| 功能（写路径） | ~20% | `executeUpdate` 必崩（P0-1）、类型化参数静默失真（P0-2）、参数化 INSERT 不可用（P0-3）、20 位整数杀连接（P0-4） |
| JDBC 规范符合度 | 70%（DBeaver 场景 85%） | Statement 结果分派语义系统性错误；若干 javadoc 强制异常缺失 |
| 测试 | 25% | 4 个 smoke 全通过，但**零 DML 覆盖**（全部写入类 P0 由此漏网） |
| 构建发布 | 55% | 脚本可重复；版本号散落 4 处、Bridge 程序集版本不符、RELEASE_NOTES 含 0x08 退格符 |
| 文档/合规 | 60% / 15% | README 与代码高度一致；**无 LICENSE、未附 LiteDB(MIT) 许可声明** |
| 健壮性 | 6.5/10 | 编码/死锁/僵尸进程处理正确；无超时、线程竞态、大结果集 OOM |

**最大工程单点风险：项目不是 git 仓库**（无版本控制/历史/tag）。

已验证正确的关键基线（修复时不得破坏）：
- DatabaseMetaData 全部结果集列序（getColumns 24 列 / getIndexInfo 13 列 / getTypeInfo 18 列等逐列核对一致）。
- SQL 重写：TOP→LIMIT、`LIMIT a,b`→`LIMIT b OFFSET a`、`select *`→`select $`、`?`→`@0`（跳过字符串字面量）、引号归一化（白名单+字面量保护）。
- Bridge 协议：stdin/stdout 行式 JSON、显式 UTF-8、stderr INHERIT、优雅关闭链（close→writer EOF→waitFor 3s→destroy→destroyForcibly）、shutdown hook。
- 密码经 stdin 传输不进命令行；事务映射正确、未提交不落盘；加密三场景报错清晰。

---

## 2. 里程碑规划

| 里程碑 | 主题 | 目标 | 预估工作量 |
|---|---|---|---|
| **M1 → v0.2.2** | 热修：让写路径"不崩"、修契约硬伤 | 全部 P0 崩溃点消除 + 一行级契约修复 + 发布链 bug | 1~2 人日 |
| **M2 → v0.3.0** | 写路径可用 + 可取消 | 类型化参数、参数化 INSERT、DELETE 方言、超时/取消、防误建库 | 3~5 人日 |
| **M3 → v0.4.0** | 健壮性与元数据真实性 | 线程安全、精度、元数据声明修正、边缘类型展示 | 3~5 人日 |
| **M4 → v1.0.0** | 工程化与可维护 | git、LICENSE、JUnit 回归、CI、版本单一来源、发布合规 | 2~4 人日 |

依赖关系：M1 无前置；M2 依赖 M1（同一批文件）；M3 与 M2 可部分并行；M4 的 FIX-403（测试套）应在 M1 后尽早启动以便持续回归。

---

## 3. 任务总表

| ID | 级别 | 任务 | 里程碑 | 预估 | 依赖 |
|---|---|---|---|---|---|
| FIX-101 | P0 | 修复 executeUpdate ClassCastException | M1 | 0.1d | — |
| FIX-102 | P1 | Statement 结果分派语义重构（execute/getUpdateCount/executeQuery/executeUpdate） | M1 | 0.5d | FIX-101 |
| FIX-103 | P1 | Json.parseNumber 溢出兜底（20 位整数杀连接） | M1 | 0.2d | — |
| FIX-104 | P2 | URL `+`/`%XX` 解码损坏路径与密码 | M1 | 0.2d | — |
| FIX-105 | P1 | Statement.close 级联关闭 ResultSet + 执行前隐式关闭 | M1 | 0.2d | — |
| FIX-106 | P1 | InvocationHandler 兜底：RuntimeException→SQLException | M1 | 0.3d | — |
| FIX-107 | P2 | release.ps1 here-string 退格符修复 | M1 | 0.2d | — |
| FIX-108 | P2 | 仓库卫生：删游离 JsonReader.cs、清 dist 无关目录 | M1 | 0.1d | — |
| FIX-201 | P0 | 类型化参数扩展 JSON 绑定（Timestamp/BigDecimal/byte[]） | M2 | 1d | FIX-106 |
| FIX-202 | P0 | 参数化 INSERT 支持（内联扩展 JSON 字面量） | M2 | 1d | FIX-201 |
| FIX-203 | P0 | `DELETE FROM` 方言重写 | M2 | 0.3d | — |
| FIX-204 | P1 | 超时与取消机制（读线程+Future+强杀恢复） | M2 | 1.5d | — |
| FIX-205 | P2 | executeBatch 抛 BatchUpdateException | M2 | 0.2d | FIX-102 |
| FIX-206 | P2 | 未绑定参数抛 SQLException；setObject targetSqlType 映射；流参数处理 | M2 | 0.5d | FIX-201 |
| FIX-207 | P1 | 防静默建库（open 前校验文件存在 + `create=true` 开关） | M2 | 0.3d | — |
| FIX-208 | P1 | Bridge 版本握手 + bridge 目录版本号排序 | M2 | 0.5d | — |
| FIX-209 | P2 | 大结果集内存策略（分页协议设计或默认保护） | M2 | 1d(设计) | — |
| FIX-301 | P2 | JDBC 对象线程安全（volatile/状态锁） | M3 | 1d | — |
| FIX-302 | P2 | 数值精度统一 BigDecimal 管道 | M3 | 1d | FIX-103 |
| FIX-303 | P2 | DatabaseMetaData 声明真实性修正 | M3 | 0.5d | — |
| FIX-304 | P3 | `IS [NOT] NULL` 重写为 `= null` / `!= null` | M3 | 0.3d | — |
| FIX-305 | P3 | NaN/MinValue/MaxValue/缺失字段展示修复（Bridge 侧） | M3 | 0.5d | — |
| FIX-306 | P3 | Bridge Console 编码 setter 容错 | M3 | 0.1d | — |
| FIX-307 | P3 | getStatement 返回真实 Statement；warning 链；setClientInfo warning | M3 | 0.5d | — |
| FIX-308 | P3 | getPropertyInfo 补全别名；getSchema/getSchemas/getTables TABLE_SCHEM 统一 | M3 | 0.3d | — |
| FIX-309 | P3 | Bridge 文档字段名大小写合并失真 | M3 | 0.3d | — |
| FIX-401 | P1 | git init + .gitignore + 初始提交打 tag | M4 | 0.2d | — |
| FIX-402 | P1 | LICENSE + THIRD-PARTY-NOTICES（LiteDB MIT 声明） | M4 | 0.2d | — |
| FIX-403 | P1 | JUnit 回归测试套件（含 DML 覆盖，吸收 probe 探针） | M4 | 1.5d | FIX-101~106 |
| FIX-404 | P2 | CI（GitHub Actions：Java 单测 + dotnet build） | M4 | 0.5d | FIX-401/403 |
| FIX-405 | P2 | 版本号单一来源（Directory.Build.props + 脚本读取） | M4 | 0.5d | — |
| FIX-406 | P2 | 发布形态决策（Maven Central vs zip+Releases）+ 兼容性声明 | M4 | 0.5d | FIX-402 |
| FIX-407 | P2 | 真实 CHANGELOG + 修订 README 中 update count 声明 | M4 | 0.3d | FIX-102 |
| FIX-408 | P2 | DBeaver 集成排查文档（日志/隔离类加载/常见报错） | M4 | 0.3d | — |

合计约 17.5 人日（含设计任务 FIX-209）。

---

## 4. 详细任务说明

### M1 热修（v0.2.2）

#### FIX-101 [P0] 修复 executeUpdate ClassCastException

- **现象**：shipped 0.2.1 jar 上，任何 `Statement.executeUpdate(...)` / `PreparedStatement.executeUpdate()` 必抛 `java.lang.ClassCastException: class java.lang.Long cannot be cast to class java.lang.Integer`。实测（探针）Statement 与 PreparedStatement 均复现；仅 `executeLargeUpdate` 正常。
- **根因**：`JdbcObjects.java:377`
  ```java
  return method.getReturnType() == Long.TYPE ? 0L : 0;
  ```
  按 JLS §15.25，三元表达式两臂为数值字面量时发生二元数值提升，**整个表达式恒为 long 类型**，与条件无关——恒装箱为 `Long`，动态代理拆箱为 `int` 返回类型时必然 CCE。
- **修复方案**（改显式分支）：
  ```java
  if ("executeUpdate".equals(name) || "executeLargeUpdate".equals(name)) {
      executeInternal(resolveSql(args), resolveParameters(args));
      if (method.getReturnType() == Long.TYPE) {
          return 0L;   // executeLargeUpdate
      }
      return 0;        // executeUpdate
  }
  ```
- **验收**：探针用例 `executeUpdate("INSERT ...")`、`prepareStatement + executeUpdate()` 不抛异常且返回 0；`executeLargeUpdate` 仍返回 0L。
- **工作量**：0.1d（含冒烟）。

#### FIX-102 [P1] Statement 结果分派语义重构

- **现象**（实测）：`execute()` 对 DML 也返回 true；`getUpdateCount()` 初始返回 0（应 -1）、任何 execute 后返回 -1（DML 后应 0）、`getMoreResults()` 后返回 0（应 -1）；`executeQuery("INSERT...")` 返回可遍历的空结果集而不抛；`executeUpdate("select...")` 静默返回 0 而不抛。
- **规范依据**：`java.sql.Statement` javadoc——execute "true if the first result is a ResultSet object"；getUpdateCount "if the result is a ResultSet object or there are no more results, -1"；executeQuery/executeUpdate 对结果类型不符必须抛 SQLException。
- **位置**：`JdbcObjects.java:368-392`（分派）、`493-512`（executeInternal 无条件物化结果集）。
- **修复方案**：
  1. 新增语句分类：取归一化 SQL 的首个关键字（跳过空白/注释），`select` / `explain` 视为产出行语句，其余（insert/update/delete/drop/…）视为更新语句。
  2. `executeInternal` 末尾按分类设置状态：产出行 → 物化 `currentResultSet`；否则 → `currentResultSet = null`，`updateCount = 0`。
  3. `execute()` 返回 `currentResultSet != null`。
  4. `getUpdateCount()`：未执行过 → -1；最近一次为更新语句 → 0；最近一次为产出行语句（含 getMoreResults 清空后）→ -1。`getLargeUpdateCount` 同步对齐。
  5. `executeQuery(sql)`：分类为更新语句时抛 `SQLException("Statement does not produce a result set: ...")`；`executeUpdate(sql)`：分类为产出行语句时抛 `SQLException("Statement produces a result set: ...")`。
  6. `getMoreResults()`：关闭并清空当前结果集，同时重置"已执行"状态使 getUpdateCount 回到 -1。
- **注意**：`explain select` 在 LiteDB 中产出行，必须归入产出行类。
- **验收**：探针覆盖四象限（execute+SELECT/execute+INSERT/getUpdateCount 三态/executeQuery(INSERT) 抛/executeUpdate(SELECT) 抛）；多结果标准循环 `while((getMoreResults()==false) && (getUpdateCount()==-1))` 正常终止。
- **工作量**：0.5d。

#### FIX-103 [P1] Json.parseNumber 溢出兜底

- **现象**：库中存在 ≥20 位 Decimal128 整数值（其他客户端写入）时，`select` 该列必抛 `NumberFormatException: For input string: "99999999999999999999"`，被 `BridgeClient.request()` 的 `catch (RuntimeException)` 捕获后 `closeQuietly` 关闭连接，该集合事实不可读（100% 复现）。
- **根因**：`Json.java:298` `return floating ? Double.valueOf(number) : Long.valueOf(number);` 无溢出兜底；`BridgeClient.java:119-121` 的 fail-fast 策略放大为连接死亡。
- **修复方案**：`parseNumber` 整数分支捕获 `NumberFormatException` 回退 `new BigDecimal(number)`；`convertValue`（`JdbcObjects.java` getXXX 链路）已支持 BigDecimal 传递，确认 `getLong/getInt` 对超范围 BigDecimal 的行为（应抛 SQLException 并包装消息，而非 NFE——并入 FIX-106 的统一包装）。
- **验收**：`Json.parse("{\"big\": 99999999999999999999}")` 返回 BigDecimal；含该字段的查询可重复执行、连接存活；`getBigDecimal` 值无损。
- **工作量**：0.2d。

#### FIX-104 [P2] URL `+`/`%XX` 解码损坏路径与密码

- **现象**（实测）：`jdbc:litedb:E:/.../a+b.litedb` 被静默打开为新建的 `a b.litedb`；URL 内嵌 `password=abc+def` 被改成 `abc def`。
- **根因**：`LiteDbConfig.java:76-77` 对 Windows 原生路径分支也调用 `URLDecoder.decode`（`:233-235`，`+`→空格）；`:89`、`:94`、`:121` 参数值同样处理。
- **修复方案**：仅当 URL 体以 `file:` 前缀或含 `?`/`&` 查询结构时做 form 解码，且解码只针对**查询参数值**，不针对数据库路径本体；自定义 decode 只展开 `%xx`、保留 `+`（先把 `+` 替换为 `%2B` 再 decode，或手写 `%xx` 扫描）。
- **验收**：路径含 `+`/`%`/空格的库可打开且不新建多余文件；`file=...;password=a+b` 连接成功；DBeaver Properties 通道（不 decode）行为不变。
- **工作量**：0.2d。

#### FIX-105 [P1] Statement.close 级联 + 执行前隐式关闭

- **规范依据**：Statement javadoc——"When a Statement object is closed, its current ResultSet object, if one exists, is also closed"；"All execution methods ... implicitly close a current ResultSet object"。
- **位置**：`JdbcObjects.java:356-361`（close 仅置标志）、`498-506`（executeInternal 不关旧 RS）。
- **修复方案**：close 时对 `currentResultSet` 调用其内部 close 逻辑；executeInternal 入口先关旧结果集。
- **验收**：execute 后取 rs 引用 → 重新 execute → 旧 rs 访问抛 SQLException(08003)；stmt.close() 后 rs 同样抛。
- **工作量**：0.2d。

#### FIX-106 [P1] InvocationHandler 兜底：RuntimeException→SQLException

- **现象**：`getInt("非数字")` 抛裸 `NumberFormatException`、`getTimestamp("非日期")` 抛 `IllegalArgumentException`、`getClob/getBlob/getArray` 等落入 `JdbcObjects.java:814` 的 `return value` 造成调用方 `ClassCastException`；DatabaseMetaData 无参调用在 `986/1057/1060-1064` 抛 NPE。
- **规范依据**：全部 getXXX 声明 `throws SQLException`；未受检异常穿透代理违反 JDBC 契约。
- **修复方案**：
  1. 各 `InvocationHandler.invoke` 外层 `catch (RuntimeException | Error)`（Error 重新抛出，RuntimeException 包装为 `SQLException(message, ex, "HY000")`）。
  2. `getClob/getBlob/getArray/getSQLXML/getNClob/getRef` 改抛 `SQLFeatureNotSupportedException`（与只读定位一致）。
  3. DatabaseMetaData 所有方法入口 `args == null` 防御。
  4. `toTimestamp` 的 `replace("Z","")` 改为正确解析 ISO-8601 偏移格式（`OffsetDateTime`/`Timestamp.toInstant` 路径）。
- **验收**：脏值 getXXX 一律 SQLException 且 cause 保留；无参元数据调用不 NPE；带 `+08:00` 偏移的时间戳可读。
- **工作量**：0.3d。

#### FIX-107 [P2] release.ps1 退格符修复

- **现象**：4 个版本的 RELEASE_NOTES.md 各含 2 个 0x08 退格符（`cat -v` 显示 `^Hridge`、`^HridgePath...^Hridge`）。
- **根因**：`scripts/release.ps1:61-90` 使用**双引号 here-string**，Markdown 反引号紧邻 `b` 开头的词（`` `bridge ``、`` `bridgePath ``）被 PowerShell 解析为 `` `b ``（退格）转义；其余反引号被静默剥掉。
- **修复方案**：改用单引号 here-string `@' ... '@`；或对反引号统一写成双写 `` `` ``。重新生成 v0.2.1 的 RELEASE_NOTES 并人工复核。
- **验收**：`grep -c $'\x08' RELEASE_NOTES.md` 为 0；反引号代码标记完整保留。
- **工作量**：0.2d。

#### FIX-108 [P2] 仓库卫生

- 删除项目根游离文件 `JsonReader.cs`（LiteDB 库内部源码拷贝，6333 字节，全工程零引用；mtime 2026-11-08 07:55，系近期混入——删除前与使用者确认来源）。
- 清理 `dist/` 内与本项目无关的目录与测试残留。
- 发布 zip 剔除 `.pdb` 调试符号（并入 FIX-405 发布脚本调整）。
- **工作量**：0.1d。

---

### M2 写路径可用（v0.3.0）

#### FIX-201 [P0] 类型化参数扩展 JSON 绑定

- **现象**（实测，危害最大）：`setTimestamp` 查 `orderDate = ?` → 0 行（原生 `DATE('...')` 48 行）；`setString(ObjectId)` 查 `_id = ?` → 0 行；`setBigDecimal` 查 Decimal → 0 行；范围查询更危险——`dt > ?`（字符串参数）**匹配全部行**、`age < ?` 同样**匹配全部行**（LiteDB 跨类型按 BsonType 枚举序比较：String=6 < Int32=2 所在数字段 < DateTime=13）。
- **根因**：`JdbcObjects.java:549-563`（normalizeParameters：Date→`Instant.toString()`、BigDecimal→`toString()`、byte[]→Base64 字符串）+ `Program.cs:541`（ToBsonValue 对 JSON String 一律 `GetString()`）。
- **官方依据**：LiteDB BsonValue.CompareTo / BsonType.cs（类型排序）、官方扩展 JSON `{"$date"}/{"$oid"}/{"$guid"}/{"$binary"}/{"$numberDecimal"}/{"$numberLong"}`（JsonReader.cs:176-183）。
- **修复方案**（已由探针验证 Bridge 无需改动）：
  1. Java 侧参数序列化为**扩展 JSON 对象**而非字符串：
     - `java.sql.Timestamp`/`java.util.Date` → `{"$date":"<ISO-8601 O 格式 UTC>"}`（纳秒截断到 LiteDB ticks 精度）
     - `BigDecimal` → `{"$numberDecimal":"<plain string>"}`（`toPlainString`，避免科学计数法）
     - `byte[]` → `{"$binary":"<base64>"}`
     - 超出 int 的 `Long` → `{"$numberLong":"<n>"}`（可选增强）
  2. 协议中参数值改为携带该 JSON 节点（Bridge 已在 `Program.cs:547` 用 `LiteDB.JsonSerializer.Deserialize` 解析 Object/Array，实测 `UPDATE ... SET d=@0,b=@1` 传 `{"$date":...}/{"$binary":...}` 回读类型 TIMESTAMP/BINARY 无损）。
  3. `setObject(i, v, targetSqlType)` 按 `Types.*` 决定目标类型（并入 FIX-206）。
- **验收**（对应探针已验证的行为）：`setTimestamp` 等值/范围查询行数与原生 `DATE()` 一致；`_id = ?` 传 ObjectId 十六进制命中 1 行；Decimal 等值查询命中；中文/emoji 字符串参数不受影响；`setNull` 行为不变。
- **工作量**：1d。

#### FIX-202 [P0] 参数化 INSERT 支持

- **现象**（第 2 轮新发现）：`INSERT INTO coll VALUES {_id:@0,...}` 在 LiteDB 5.0.21 SQL 解析器报 `Unexpected token '@'`——**参数化 INSERT 完全不可用**（方言限制，非驱动 bug，驱动此前无任何替代路径）。普通字面量 INSERT 经 `execute()` 可落盘。
- **修复方案**（探针已验证内联路线可行）：对 INSERT 语句，`SqlParameterBinder` 不做 `?`→`@N` 改写，而是把每个 `?`（跳过字符串字面量内）直接替换为该参数的**扩展 JSON 字面量**（复用 FIX-201 的序列化器；字符串值用自研 Json writer 序列化保证转义正确；日期/二进制/Decimal 落为 `{"$date":...}` 等）。UPDATE 的 `SET x=@0` 已实测支持参数，维持 `@N` 路径。
- **备选方案**（若内联稳定性不足）：Bridge 新增 `insert(collection, docs[])` 方法走官方 Insert API，Java 侧解析 INSERT 语法构造文档——工作量约 2d，列为 fallback。
- **验收**：`prepareStatement("INSERT INTO t VALUES {_id: ?, name: ?}")` + 各类型参数 → 落库且类型正确（回读 TIMESTAMP/BINARY/DECIMAL）；字符串含引号/反斜杠/中文往返无损；null 参数落为 BsonNull（需专项确认 LiteDB 对 null 字面量的接受性）。
- **工作量**：1d。

#### FIX-203 [P0] `DELETE FROM` 方言重写

- **现象**（第 2 轮新发现）：标准 SQL `DELETE FROM t1 WHERE ...` 被 LiteDB 拒绝（`Unexpected token 't1'`），必须写 `DELETE t1 WHERE ...`。驱动对 SELECT 做了 TOP/LIMIT 转换却漏了 DELETE。DBeaver 与常见 ORM 生成的都是标准 `DELETE FROM`。
- **修复方案**：`rewriteCommonSql` 增加规则：`\bDELETE\s+FROM\s+(标识符)` → `DELETE 标识符`，复用现有单引号/双引号状态机跳过字符串字面量（与 `rewriteSelectTop` 同框架）。UPDATE 语法 LiteDB 原生接受 `UPDATE t SET ...`，无需处理。
- **验收**：`DELETE FROM t WHERE ...` 经 JDBC 落库生效；字面量含 "delete from" 文本不被误改；表名带引号/方括号时先走归一化再重写。
- **工作量**：0.3d。

#### FIX-204 [P1] 超时与取消机制

- **现象**：`BridgeClient.java:87` `reader.readLine()` 无限阻塞；`cancel()`/`setQueryTimeout()`/`setNetworkTimeout` 全为 no-op（`JdbcObjects.java:308-314, 420-423`）；Bridge 挂起（如 direct 模式等文件锁）则连接永久挂死，DBeaver"取消查询"无效；`close()` 与 `request()` 竞争同一 monitor，挂死时连 close 都会阻塞。
- **修复方案**：
  1. BridgeClient 增加独立读线程：循环 `readLine`，按响应 id 完成 `CompletableFuture`；EOF/异常时以异常完成全部 pending。
  2. `request(msg, timeout)` 用 `future.get(timeout)`；`TimeoutException` → `process.destroyForcibly()` + 标记连接不可用 + 抛 `SQLException(..., "HYT01")`（connection failure due to timeout）。
  3. `setQueryTimeout` 存值并在 execute 时生效；`cancel()` 对在途请求触发同样的中止路径。
  4. **事务语义取舍（需在实现时明确）**：强杀 Bridge 会丢失进行中的事务（LiteDB 未提交回滚）。推荐策略：有活动事务时超时/取消仅关闭连接并给出明确错误；无事务时销毁 Bridge 并支持下次请求自动重开（透明恢复）。
  5. `isValid(timeout)` 已真实 ping（复核确认非 no-op），仅补 `timeout < 0` 抛 SQLException（并入 FIX-307 批次亦可）。
- **验收**：模拟挂起 Bridge（测试用 sleep 桩）→ executeQuery 在设定超时后抛 SQLException 而非永久阻塞；cancel 后连接状态一致；无事务场景超时后重连自动恢复。
- **工作量**：1.5d。

#### FIX-205 [P2] executeBatch 抛 BatchUpdateException

- **规范依据**：Statement javadoc Throws: BatchUpdateException；`supportsBatchUpdates=true` 已声明（`JdbcObjects.java:988-990`），契约必须兑现（否则改报 false，二选一，推荐前者）。
- **修复方案**：executeBatch 逐条执行收集计数，失败时抛 `BatchUpdateException(message, sqlState, counts, cause)`；计数按当前能力恒 0（README 已声明），至少长度正确。
- **验收**：批中混入坏 SQL → BatchUpdateException 且 counts 数组长度正确。
- **工作量**：0.2d。

#### FIX-206 [P2] PreparedStatement 参数契约

- **现象**：未绑定参数静默按 null 执行（`resolveParameters` 对缺失索引补 null，`JdbcObjects.java:515-525`）；`setObject(i, v, targetSqlType)` 的类型参数被忽略；`setCharacterStream/setBinaryStream` 传入的 Stream 对象被 `String.valueOf` 成 `java.io.ByteArrayInputStream@...` 字符串发给 Bridge。
- **修复方案**：执行前校验 1..parameterCount 全部已设置（`clearParameters` 重置），缺失抛 SQLException；`setObject` 按 targetSqlType 转换（TIMESTAMP→Timestamp、DECIMAL→BigDecimal、BINARY/VARBINARY→byte[]、其余按 setObject 通用规则）；流参数读取为 byte[]/String 后走对应 setter。
- **验收**：漏设参数执行抛 SQLException；`setObject(i, "2026-01-01", Types.TIMESTAMP)` 行为符合约定（或明确抛不支持——与元数据声明一致）。
- **工作量**：0.5d。

#### FIX-207 [P1] 防静默建库

- **现象**（探针实测）：对不存在的路径带 password 打开 → LiteDB 静默创建新加密库（拼写错误无保护，用户看到"空库"而非报错）。
- **修复方案**：Bridge `Open()` 前校验 `File.Exists(filename)`，不存在且连接串无 `create=true` → 返回结构化错误（消息含"文件不存在，如需新建请加 create=true"）；Java 侧 `LiteDbConfig` 接受 `create=` 属性透传。已有库的打开路径不受影响。
- **验收**：不存在路径 → 干净报错；`create=true` → 可新建；既有 4 个 smoke 行为不变（smoke 会新建 sample 库，需同步加 `create=true` 或先建文件）。
- **工作量**：0.3d。

#### FIX-208 [P1] Bridge 版本握手与目录选择

- **现象**：open 响应含 `bridgeVersion` 但驱动不校验（新驱动配旧 Bridge 只会运行时报 "Unsupported bridge method"）；`BridgeClient.java:297-304` 目录发现按 mtime 排序，`bridge-0.1.0` 若解包时间更新会被选中；`bridgePath` 配置无效时静默回退 jar 旁捆绑 Bridge（探针实测）。
- **修复方案**：1) 目录名按版本号解析排序（`bridge-(\d+\.\d+(\.\d+)?)`），解析失败排最后；2) open 后比对 Bridge 与驱动的 major.minor，不匹配记 warning（stderr 日志）并在错误消息中提示；3) `bridgePath` 显式配置但无效 → 直接报错不回退；仅"未配置 bridgePath"时才自动发现（回退时打 warning）。
- **验收**：隔离 jar + 无效 bridgePath → 明确报错；混合版本目录 → 选中最高版本；版本不匹配 → 有可诊断信息。
- **工作量**：0.5d。

#### FIX-209 [P2] 大结果集内存策略（设计任务）

- **现象**：Bridge 全量物化（`maxRows=0` 无限制）→ JSON 行 → Java String → Json 对象 → QueryResult List，同份数据 4+ 份副本；用户关闭 DBeaver 行数限制后浏览大集合易 OOM。
- **方案选项**（设计评审后实施）：
  A. 协议分页：Bridge 支持 `offset+limit` 迭代取页（LiteDB `Find(offset, limit)` 原生支持），Java 侧按需拉页——改动中等，收益最大；
  B. 保守兜底：默认 `maxRows` 上限（如 10000，可配置），超限截断并在 ResultSetMetaData/警告中说明——半天工作量；
  C. 行级流式：Bridge 逐行 flush + Java 逐行解析，保持物化 List 但去掉 String 大副本。
- **验收**：100 万行集合浏览不 OOM（方案 A/B）；现有 smoke 不回归。
- **工作量**：1d（设计）+ 实施另行计入。

---

### M3 健壮性与元数据真实性（v0.4.0）

#### FIX-301 [P2] JDBC 对象线程安全
- **现象**：`BridgeClient.request` 的 synchronized 只保证协议不交错；上层状态裸奔——`ConnectionState.closed/autoCommit/readOnly`（`JdbcObjects.java:139-172`）非 volatile；`setAutoCommit` 的 check-then-act（221-235）可重复 begin/commit；`StatementHandler.parameters/maxRows/currentResult`（333-343）、`ResultSetHandler.row/lastWasNull`（583-587）无同步。DBeaver 会在同一 Connection 上并发跑元数据浏览与查询。
- **修复方案**：`closed` 等 flag 声明 volatile；有状态复合操作（setAutoCommit/事务边界/参数装配+执行）以 `synchronized(state)` 包裹；ResultSet 读取路径加轻量锁（同一 ResultSet 跨线程使用本就不合规，防内部状态撕裂即可）。
- **验收**：并发压力探针（多线程 setAutoCommit+execute、元数据+查询混跑）无 CME/无事务边界错乱。
- **工作量**：1d。

#### FIX-302 [P2] 数值精度统一 BigDecimal 管道
- **现象**：带小数数值一律 `Double.valueOf`（`Json.java:279-298`），LiteDB Decimal128（28-29 位有效数字）静默截断为 double 的 15-17 位；元数据却声明 DECIMAL/BigDecimal。
- **修复方案**：`parseNumber` 对含小数点的数字解析为 BigDecimal（保留原始文本精度）；`convertValue` 中 `getDouble/getFloat/getInt/getLong` 从 BigDecimal 转换（注意 Double.NaN 语义——Bridge 端 NaN 已序列化为 null，无冲突）；`getObject` 对 DECIMAL 列返回 BigDecimal（顺带修复"getColumnClassName 与 getObject 实际类型不一致"，`JdbcObjects.java:759-761, 1997-1998`）。
- **验收**：28 位有效数字 Decimal 往返无损；`getDouble` 数值正确；getObject(DECIMAL) 返回 BigDecimal。
- **工作量**：1d（含回归）。

#### FIX-303 [P2] DatabaseMetaData 声明真实性修正
- **修正清单**（默认兜底 false/0 造成失实，`JdbcObjects.java:1161` 兜底分支 + 各常量）：
  - `supportsMixedCaseQuotedIdentifiers()` / `storesMixedCaseQuotedIdentifiers()` → **true**（LiteDB 字段名大小写敏感；DBeaver 依赖此标志决定是否给标识符加引号，误报 false 会导致大小写敏感列名被错误小写化）
  - `nullsAreSortedAtEnd/AtStart/High/Low` → 与 LiteDB 实际一致（Null 在 BsonType 序列最前，`Program.cs`/BsonType.cs）
  - `supportsLikeEscapeClause()` → false（LiteDB LIKE 无 ESCAPE）
  - `setTransactionIsolation` 拒绝 TRANSACTION_NONE 及不支持级别抛 SQLException（与 `supportsTransactionIsolationLevel`、`getDefaultTransactionIsolation` 三处自洽，`JdbcObjects.java:272-277, 1053-1058`）
  - `autoCommit 下 commit()/rollback()` 抛 SQLException（javadoc Throws 明确要求）
  - `getIndexInfo` ORDINAL_POSITION 不再恒 1：按 Bridge 返回的索引列序填充（若 Bridge 每索引仅返回一行，则修 Bridge 端展开复合索引）
  - `getColumns("_id") IS_AUTOINCREMENT` 与 `getPrimaryKeys` 声明一致化
- **验收**：DBeaver 中大小写敏感列名过滤不再小写化； isolation 三处声明自洽。
- **工作量**：0.5d。

#### FIX-304 [P3] `IS [NOT] NULL` 重写
- LiteDB 官方方言无 `IS NULL`（实测 `Unexpected token 'is'`），DBeaver NULL 图形过滤会直接报错。在 `rewriteCommonSql` 增加规则：`is [not] null` → `= null` / `!= null`（跳过字符串字面量）。
- **工作量**：0.3d。

#### FIX-305 [P3] Bridge 边缘 BsonValue 展示
- `Program.cs:442-444, 468`：MinValue/MaxValue/缺失字段全折叠为 NULL，类型信息丢失。改用官方扩展 JSON 字符串表示（`{"$minValue":"1"}` / `{"$maxValue":"1"}`），TypeName 相应标注；非有限 double（NaN/Infinity）写 null（对齐官方 JsonWriter.cs:71-79，消除 "response id mismatch" 掩盖真实错误的问题，`Program.cs:57,61` + `BridgeClient.java:99-101`）。
- **工作量**：0.5d。

#### FIX-306 [P3] Bridge Console 编码 setter 容错
- `Program.cs:7-8` 的 `Console.InputEncoding/OutputEncoding` setter 在无控制台句柄环境可能抛异常（dotnet/runtime#28625）。改为 try/catch 或 `OpenStandardInput` + 显式 `StreamReader/StreamWriter`（UTF-8）。
- **工作量**：0.1d。

#### FIX-307 [P3] Statement/Connection 契约补齐
- `getStatement()` 返回真实 Statement（`JdbcObjects.java:697-699` 现返回 null）；warning 链最小实现（SQLWarning 持有 + getWarnings/clearWarnings）；`setClientInfo` 未识别属性产生 warning；`setSavepoint` 等未实现方法改抛 `SQLFeatureNotSupportedException`（替代静默默认值）；`closeOnCompletion/isCloseOnCompletion` 实现；`setLargeMaxRows/getLargeMaxRows` 接通；`abort(null)` 校验。
- **工作量**：0.5d。

#### FIX-308 [P3] 配置与元数据一致性
- `getPropertyInfo` 暴露全部实际接受的键（parse 另接受约 17 个未声明别名：databasePath/filename/path/dataSource/pwd/readonly/connectionMode/columnSample/sampleSize 等，`LiteDbDriver.java:45-65` vs `LiteDbConfig.java:53-67`）。
- `getSchema()` 返回 null（非 ""）并与 `getSchemas()`/getTables TABLE_SCHEM 统一（三处现互相矛盾，`JdbcObjects.java:266-268, 1079-1085`）。
- **工作量**：0.3d。

#### FIX-309 [P3] Bridge 文档字段名大小写合并失真
- `Program.cs:419`（ExtractCells 用 OrdinalIgnoreCase 字典）与 `:183-197`（列合并同比较器）：同文档 `{"Name":1,"name":2}` 互相覆盖。改为区分大小写字典 + 列去重策略（保留两列或标注冲突）。
- **工作量**：0.3d。

---

### M4 工程化（v1.0.0）

#### FIX-401 [P1] 引入版本控制
- `git init` + `.gitignore`（`dist/`、`release/`、`obj/`、`bin/`、`*.litedb` 测试库、`.mimosa/`）；初始提交后打 `v0.2.1` 回溯 tag（以 release 产物为基线），此后每里程碑打 tag。
- **工作量**：0.2d。

#### FIX-402 [P1] LICENSE 与第三方声明
- 项目自身 LICENSE（建议 MIT，待定者决策）；`THIRD-PARTY-NOTICES.md` 附 LiteDB 5.0.21 的 MIT 版权与许可声明（分发 `LiteDB.dll` 的合规要求）；release.ps1 打包时将两文件装入 zip。
- **工作量**：0.2d。

#### FIX-403 [P1] JUnit 回归测试套件
- **背景**：本轮全部写入类 P0（executeUpdate CCE、参数化 INSERT、DELETE FROM、类型化参数）能存活至今，根因是测试套零 DML。
- **内容**：
  1. 引入 JUnit 5（仅测试作用域，保持驱动运行时零依赖）。
  2. 纯 Java 单测：`LiteDbConfig.parse`（URL/别名/`;`/`?`/mojibake/`+` 路径）、`rewriteCommonSql` 全规则（含字面量保护反例）、`SqlParameterBinder`、`Json`（转义/代理对/溢出/深嵌套）。
  3. 集成测试（真实 Bridge）：把复核探针 `E:\AI\litedb-review-tmp\probe\Probe1~6.java` 整理为回归用例——executeUpdate 返回类型、扩展 JSON 参数往返（TIMESTAMP/BINARY/BIGINT/DECIMAL）、参数化 INSERT、DELETE FROM、20 位整数、错误密码/缺文件/坏 bridgePath 负面用例。
  4. 内容级断言补强：中文/emoji 值往返（现仅有列类型推断断言）；事务 commit/rollback 落盘断言。
- **验收**：`mvn test`/`gradle test`（或现有 build.ps1 扩展 test 目标）一键全绿；DML 覆盖率不为零。
- **工作量**：1.5d。

#### FIX-404 [P2] CI
- GitHub Actions：Java 单测（JDK 11/17/21 矩阵）+ dotnet build + smoke。依赖 FIX-401（仓库托管后才有 CI；未托管前保留本地 `scripts\run-tests.ps1`）。
- **工作量**：0.5d。

#### FIX-405 [P2] 版本号单一来源
- 现状：0.2.1 散落 `build.ps1`/`release.ps1`/`smoke.ps1` 三个默认参数 + `LiteDbDriver.MAJOR/MINOR`；Bridge 程序集无版本（`--version` 报默认 1.0.0）。
- 方案：`Directory.Build.props` 定义 `<Version>`；csproj 引用；ps1 脚本从 props 读取（或 build.ps1 参数注入 csproj `-p:Version=`）；`LiteDbDriver` 版本常量由构建生成（或从 jar manifest 读取）。release zip 剔除 .pdb。
- **工作量**：0.5d。

#### FIX-406 [P2] 发布形态决策与兼容性声明
- 二选一并写入 README：a) Maven Central（需 GPG 签名/sources/javadoc jar/POM 许可元数据，Sonatype 要求）；b) 继续 zip + GitHub Releases（推荐起步，工作量小）。
- README 增加"版本兼容声明"：Java 11+、.NET 8 runtime、LiteDB 5.x、JDBC 4.x 子集（明确列出未实现特性）。
- **工作量**：0.5d。

#### FIX-407 [P2] CHANGELOG 与 README 修订
- 真实逐版本 CHANGELOG（替代固定模板）；修订 README "update counts are reported as 0" 等已失实声明（FIX-102 落地后改为准确描述）；补 `create=true`、`IS NULL` 限制等新行为说明。
- **工作量**：0.3d。

#### FIX-408 [P2] DBeaver 集成排查文档
- 内容：驱动日志位置、自定义驱动的类加载器隔离问题（dbeaver#20097 类问题）、drivers.xml 错误排查（dbeaver#37935）、bridgePath 未配置时的回退行为说明（对接 FIX-208）。
- **工作量**：0.3d。

---

## 5. 回归基线与验收总纲

1. **既有 4 项 smoke 必须全程保持绿色**（SmokeTest ×2 配置、ComplexDbSmokeTest ×2 库）。涉及 `create` 行为的改动（FIX-207）需同步调整 smoke 建库方式。
2. **每个 P0/P1 任务必须附带可重复的自动化用例**（进 FIX-403 套件），禁止"手工测过"作为唯一验收。
3. **探针资产回收**：`E:\AI\litedb-review-tmp\probe\` 下的 6 个探针程序是现成回归素材，FIX-403 时整理入库后删除临时目录。
4. **不改动的部分**：DatabaseMetaData 列序、Bridge 关闭链、编码双保险、密码 stdin 传输——这些已验证正确，重构时保持行为兼容。
5. 每个里程碑收尾：跑全套 smoke + 新增用例 + 更新 CHANGELOG + 打 tag。

## 6. 风险与依赖

| 风险 | 影响 | 缓解 |
|---|---|---|
| LiteDB 方言跨版本差异（如 `@` 在 INSERT 中被拒） | FIX-202 路线失效 | 已锁定 5.0.21 实测；csproj 固定版本；备选 Bridge Insert API 方案 |
| FIX-204 强杀 Bridge 丢事务 | 用户数据误解 | 有事务时 fail-fast 报错策略；文档明示 |
| FIX-301/302 触碰核心取值链路 | 读路径回归 | 先落 FIX-403 基线测试再动手；保持 4 项 smoke 全绿 |
| `executeUpdate` 修复后 DBeaver 展示变化（N rows affected） | 无 | 正向变化 |
| 非 git 仓库期间多人编辑 | 覆盖丢失 | FIX-401 应最先执行（可提前至 M1 一并做） |

## 7. 建议执行顺序（关键路径）

```
FIX-401(git) → FIX-101 → FIX-103 → FIX-106 → FIX-102 → FIX-105   ← M1 主体（1.5d）
           ↘ FIX-104 → FIX-107 → FIX-108                          ← M1 其余（并行）
FIX-201 → FIX-202 → FIX-203 → FIX-204                             ← M2 关键路径（3.8d）
FIX-403（测试基线）尽早插入 M1 结束后
M3 按表并行推进；M4 中 FIX-402/405/407 随 v0.3.0 发布一并落地可提前
```

> 说明：本计划中的行号基于 2026-11-08 的 v0.2.1 源码；实施时以根因描述为准定位。
