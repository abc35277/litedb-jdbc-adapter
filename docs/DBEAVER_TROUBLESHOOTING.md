# DBeaver 集成排查指南（FIX-408）

## 1. 驱动不出现在驱动列表

- 确认安装脚本写入的是当前工作区配置：`%APPDATA%\DBeaverData\workspace6\.metadata\.config\drivers.xml`（安装脚本默认目标）。多工作区用户需确认实际使用的 workspace。
- 修改 drivers.xml 后重启 DBeaver。
- XML 语法错误会导致 "Can't create driver instance"（参见 dbeaver#37935）：用 XML 编辑器校验 `dist\dbeaver\drivers.xml`，确认 `<driver id="litedb_jdbc" class="com.litedb.jdbc.LiteDbDriver">` 与 `<library path="..."/>` 指向存在的 jar。
- 自定义驱动的类加载器隔离是常见坑（dbeaver#20097 类问题）：不要把其它驱动 jar 混进同一个 "Libraries" 列表。

## 2. 连接报 "Cannot locate LiteDB bridge"

- 驱动在 jar 同级目录按 `bridge-*`（按解析版本号降序）与 `bridge/` 自动发现 Bridge。zip 解包后必须保持 jar 与 `bridge/` 目录同级。
- `bridgePath` 属性**必须有效**：显式配置了无效路径会直接报错（不会静默回退到捆绑 Bridge）。删掉该属性即可回退到自动发现。
- 也可用 JVM 系统属性 `-Dlitedb.jdbc.bridge=...` 或环境变量 `LITEDB_JDBC_BRIDGE`。

## 3. 打开数据库报 "file was not found ... Pass create=true"

1.0.0 起，打开不存在的文件默认报错（防止拼写错误静默建出空库）。确要新建库时在 URL 或驱动属性加 `create=true`。

## 4. 查询挂起 / 取消无效（0.x 版本旧知）

1.0.0 起 `setQueryTimeout` / `setNetworkTimeout` / `Statement.cancel()` 已实装：
- 挂起的 Bridge 在超时后被强制结束（SQLState HYT01），无事务时连接自动重建并重试；
- 取消（SQLState 57014）立即解除调用方阻塞，连接保持可用。

如果 DBeaver 的“取消查询”仍无响应，为连接设置 `connectTimeout` 与查询超时（DBeaver: 编辑连接 → SQL 编辑器 → 查询超时秒数）。

## 5. 时间/金额/二进制条件查不到数据

0.x 版本把类型化参数降级为字符串，导致跨类型比较静默失败。1.0.0 已修复——务必使用 1.0.0 驱动，并用 `setTimestamp` / `setBigDecimal` / `setBytes` 绑定；按 `_id`(ObjectId) 查询请继续使用字符串十六进制值。

## 6. 大集合浏览（10 万行级）性能设置

对大表（如 22.9 万行的日志表），DBeaver 有三个设置直接决定翻页速度（窗口 → 首选项 → 编辑器 → 数据格式/结果集）：

1. **"Refresh data on next page reading"（读取下一页时刷新数据）→ 建议关闭**。默认开启时 DBeaver 每翻一页都**从头重新执行查询并丢弃已读行**（dbeaver#22028），大表越翻越慢；关闭后使用同一游标继续读，配合本驱动的流式分块，每页只取新行。
2. **"Use SQL to limit fetch size"（使用 SQL 限制获取大小）→ 保持关闭**。开启后 DBeaver 改写 SQL 加 LIMIT/OFFSET，而 LiteDB 的 OFFSET 是逐文档跳过（深页 O(n)，22.9k 行的 offset 200000 实测 36 秒）。
3. **"Result Set Max Size"（结果集最大行数）保持适度（默认 200）**。驱动会把 `setMaxRows` 透传到桥端截断，页大小适度 = 单页内存与延迟都小。

其他要点：
- 表统计（行数）已由驱动拦截走索引计数（v1.3.1 起，秒级）。
- **排序/过滤字段建索引**是最大杠杆：`CREATE INDEX idx_msg_seq ON message_log (payload.seq)` 后，22.9 万行的排序从 132 秒降到毫秒级；无索引的 ORDER BY/WHERE 是全扫+排序。
- 驱动已实现 `setFetchSize` → 桥端按该粒度分块（v1.3.1 起），DBeaver 页大小与流批次对齐。

## 7. 大集合浏览内存占用高

默认 `maxRows=10000` 兜底。DBeaver 的行数限制（默认 200）优先于驱动默认值。需要全量导出时在 URL 加 `maxRows=0` 并自行承担内存开销。

## 7. 日志与诊断

- Bridge 的 stderr 直通 DBeaver 进程控制台（`redirectError(INHERIT)`）；无法匹配的响应会打印 `LiteDB bridge: unmatched response`。
- 驱动侧唯一诊断输出走 stderr；版本握手不匹配会给出可读提示。
- 隔离测试：用 `scripts\run-tests.ps1` 跑完整门禁（build+smoke+回归），确认问题在驱动还是 DBeaver 集成层。
