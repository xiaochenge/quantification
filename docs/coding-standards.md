# 编码规范（Coding Standards）

> 写代码前必读。所有规则对本人和 AI 助手（含后续换用的更便宜的模型）同样适用。
> 最后更新：2026-09-22

## 1. 注释（强制，最重要）

代码"能读懂"是第一优先级。没有注释的代码视为未完成。

- **每个类**：必须有 Javadoc，说明"这个类是干什么的、在整个系统里处于什么位置"。
- **每个 public 方法**：必须有 Javadoc，说明做什么、参数含义、返回值；抛异常的要写 `@throws`。
- **每个字段**（尤其是实体类字段）：必须有注释，说明含义、单位、取值范围。金额要写清币种/单位，比率要写清是小数还是百分比。
- **数据库**：每张表、每个字段都要写 `COMMENT`；建表脚本头部写清这个迁移的用途。
- **配置项**：`application.yml` 里每个不直观的配置都要有行内注释，说明作用与默认值。
- **注释内容**：写"为什么这么设计""这个字段代表什么"，不要复述代码字面意思（`i++ // i 加一` 这类无意义注释不允许）。
- **语言**：中文。

例外：getter / setter 这类纯样板方法可以不加注释，前提是对应字段已有注释。

## 2. 时间

- **数据库统一用 `DATETIME` 存可读时间**，禁止用 `BIGINT` 存毫秒时间戳。
- 时区统一 `Asia/Shanghai`（数据库连接串里已指定 `serverTimezone`）。
- Java 侧统一用 `LocalDateTime`。
- 交易所传的是毫秒时间戳，**只在"调接口 / 解析响应"的边界处转换一次**，不要在业务代码里到处转。
- 命名：时间字段以 `_time` 或 `_at` 结尾（如 `funding_time`、`next_update_time`、`created_at`），
  不要用 `*_ts` / `*_timestamp`。

## 3. 金额与数量

- 数据库用 `DECIMAL(38,18)`。
- Java 用 `BigDecimal`，**禁止** `double` / `float`（浮点误差在资金场景不可接受）。
- 从交易所拿到的是字符串，直接 `new BigDecimal(str)`，不要先转 double。

## 4. 包结构

按层分包，扁平结构：

```
com.quantification
├── Application          启动类
├── admin                接口层（管理后台 REST，一期只读）
├── service              业务层（采集、分析、策略等）
├── mapper               数据访问层（MyBatis Mapper 接口）
├── entity               实体（与数据库表一一对应）
└── bitget               交易所客户端（Bitget SDK 封装 / 自建客户端）
```

- 新增业务代码时按上面归位，不要随手塞进已有的包里。
- 类职责单一：客户端只负责调接口，service 负责业务逻辑，mapper 只负责 SQL。

## 5. 数据库迁移（Flyway）

- 脚本放在 `src/main/resources/db/migration/`，命名 `Vn__描述.sql`。
- **设计阶段**（当前）：表结构还没冻结，允许直接改 `V1` 并重置本地测试库。
- **schema 冻结后**：只允许追加新脚本，**已执行过的脚本一律不改**（Flyway 会校验 checksum）。
- 迁移脚本必须可重复执行（用 `INSERT IGNORE` / `ON DUPLICATE KEY UPDATE` 等保证幂等）。

## 6. 幂等与唯一键

事件型数据必须能用唯一键去重，重复采集不能产生重复行：

| 数据 | 唯一键 |
| --- | --- |
| 历史资金费率 | `symbol` + `funding_time` |
| 成交明细 | `exec_id`（真实）/ `dedup_key`（模拟与真实统一） |
| 账户财务流水 | `record_id` |
| 订单 | `client_oid` |

## 7. 命名

- 数据库：表名单数（`watch_coin`）、字段下划线（`base_coin`）。
- Java：类名大驼峰、字段与方法小驼峰；布尔语义用 `enabled` / `is` 前缀这类明确词，不用 `flag`。
- 交易对 / 币种等字符串统一大写，与交易所返回一致（`BTCUSDT`、`USDT-FUTURES`）。

## 8. 安全（铁律）

- **密钥只能放仓库外**：`~/.quantification/application-local.yml`，权限 `600`。
  任何场景下都不许进仓库、不许打进 jar、不许对外提供。
- 不要在 `src/main/resources/` 下创建 `application-local.yml`，也不要把密码写进 `application.yml`。
- 构建与提交都有自动校验（`scripts/check-secrets.sh`），命中密钥会直接失败；
  **不要绕过它**（提交时不要用 `--no-verify`，也不要注释掉 pom 里的校验）。
- 提交前用 `git status` 自查，确认没有敏感文件被带上。
