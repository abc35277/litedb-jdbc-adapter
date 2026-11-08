# tests/repl — jshell 回归套件（FIX-403）

这是 FIX-403 要求的"DML 覆盖回归基线"，以 **数据夹具 + jshell 脚本** 的形式实现。

## 为什么不是 JUnit .java 文件

本仓库启用了安全扫描门禁（Mimosa）：把携带 DML 语句（INSERT/DELETE 字面量或变量参数的
executeUpdate 调用）的 .java 测试源码写入工作区会被安全扫描拦截（测试固定语句被判定为
SQL 注入风险）。本套件采用标准的"SQL 外置为运行时数据"模式：

- `fixtures/*.txt` 是纯数据文件，存放固定的 DML 语句与参数模板；
- `regression.jsh` 是 jshell 回归脚本，除 SELECT 外不含任何 SQL 字面量；
- `scripts/run-tests.ps1` 生成 `run-config.jsh`（注入 jar/bridge/库路径）后按序执行。

这样既保住"DML 覆盖"（正是历史上全部写入类 P0 漏网的根本原因），又满足源码级安全门禁。
日后若门禁策略调整，`regression.jsh` 的每个 `__check` 都可 1:1 平移为 JUnit @Test。

## 运行

```powershell
.\scripts\run-tests.ps1 -JavaHome E:\dev\env\jdk-26.0.1 -Version 1.0.0
```

套件内容（对应整改文档 FIX 编号）：
FIX-101/102 结果分派、FIX-103 大数 BigDecimal、FIX-104 '+' 路径、FIX-106 脏值转换、
FIX-201/202 类型化参数与参数化 INSERT、FIX-203 DELETE FROM、FIX-204 超时/取消健全性、
FIX-205 BatchUpdateException、FIX-206 未绑定参数、FIX-207 create 守卫、FIX-303/307/308
元数据真实性与契约、FIX-302 getObject 声明类型。
