# quantification 项目交接与约定

> 这个文件会被 Codex 在每次新会话开始时自动读取。**新会话请先读这一份，再读 `docs/` 里的四份文档。**
> 最后更新：2026-09-24

## 项目是什么

资金费率套现（funding rate arbitrage）。**只在 Bitget 一家交易所**操作：同时监控多个币种，
对费率处于高位的币种建立 **delta 中性仓位（现货多 + 永续空）** 吃资金费，按费率变化动态调仓。

- 目标：**理想总体年化 8%**（弹性目标，非保底）；底线是"宁可空仓，也不亏钱"
- 账户类型：**统一账户（UTA）**，走 `/api/v3` 接口
- 上线节奏：先在**官方模拟盘**跑通流程，再考虑真实资金

**权威设计文档是 `docs/` 下的四份**；本文件只记录环境、约定、进度和待办。

部署形态：**当前这台 Mac mini 只作开发机**，将来另买一台 Mac mini 当服务器跑正式。
代码要按"开发机开发验证、服务器跑正式"来写——配置靠 profile 区分，不写死本机路径。

## 当前状态（里程碑：2026-09-24 · 自建 mock 已落地）

### ✅ 已完成

| 部分 | 内容 |
| --- | --- |
| **数据层（模块 1、2）** | Flyway 迁移 V1~V5（16 张表）；自建 Bitget 客户端（公共 + 私有，含签名、模拟盘开关、网络重试） |
| **采集** | 篮子自动同步（成交额 ≥500 万 + 折扣率 ≥0.8 + 现货/合约都有）、历史/实时资金费率、交易对规则、真实手续费率 |
| **策略层（模块 3~5）** | 费率分析（10 天窗口净年化）、持仓决策、FOK 下单 + 成交反查 + 补单/回退 |
| **风控（模块 6）** | 回撤熔断、保证金率预警、轻量失败不熔断（只跳过） |
| **对账（模块 7）** | 启动时以交易所为准接管持仓 |
| **自建 mock（模块 9）** | `ExchangeGateway` 双实现（Real / Mock）；按真实盘口撮合并记滑点；模拟账本落库（`trade_order` / `trade_fill` / `mock_account` / `mock_position`）；**资金费自己算**；净值曲线 + 只读接口 |
| **安全加固** | 密钥移出仓库到 `~/.quantification/`，构建 + 提交双重校验 |
| **需求设计** | 二阶段 10 个模块全部定稿（见 `docs/phase-2-strategy-design.md`） |

### 🔄 进行中

**官方模拟盘流程验证**（用 BTC 测整个链路）。已查明模拟盘的关键限制（见下"模拟盘实测结论"）。
**收益验证改走自建 mock**（`simulation.enabled=true`），它已能跑完整候选池。

### ⏳ 未完成

| 项 | 说明 |
| --- | --- |
| 盈亏核算（模块 8） | 未实现 |
| 管理后台（模块 10） | 未实现（技术选型已定：Spring 只读 REST + Vue3/Element Plus/ECharts） |
| 邮件告警 | 配置项已留，**发送逻辑未实现** |
| 费率恢复轮数过滤 | 回测里有，**策略代码里还没加** |
| 订单/成交落库 | **mock 路径已落库**（`trade_order` / `trade_fill`）；实盘路径还没接"反查订单 → 更新本地状态机" |
| 下架监控 | 监听公告，持仓币种下架时平仓（一期人工，二期自动） |
| `mgnRatio` 口径 | 已实测确认 = `mmr ÷ effEquity`，但预警/减仓阈值尚未落地 |

## 技术栈与版本

| 项目 | 实际使用 |
| --- | --- |
| JDK | 24（`~/Library/Java/JavaVirtualMachines/openjdk-24.0.2+12-54`） |
| 构建 | Maven 3.9.16，项目自带 `./mvnw` |
| 框架 | **Spring Boot 4.1.1**（parent） |
| 持久层 | **MyBatis 4.1.0**（原生 Mapper，注解 SQL） |
| 数据库 | **MySQL 8.4.11**，已装在 `~/Library/MySQL`，监听 `127.0.0.1:3306` |
| 结构迁移 | **Flyway 12.4.0**（`spring-boot-starter-flyway` + `flyway-mysql`） |
| JSON | Jackson 3（databind 在 `tools.jackson`，注解仍是 `com.fasterxml.jackson.annotation`） |
| 交易所 | **自建轻量客户端**（不引官方 SDK——它没发布到 Maven 且依赖太旧），见下方说明 |
| 前端 | Vue 3 + Element Plus + ECharts（未开工） |

### 为什么不用 Bitget 官方 Java SDK

官方 SDK 没发布到任何 Maven 仓库（Central / Sonatype 均 404），且是 Java 8 + 老依赖
（lombok 1.16.20、fastjson 1.2.70），在 JDK 24 上无法编译。**改用 Spring 自带的 `RestClient`
直接调 REST 接口**，签名逻辑自行实现（参考官方文档）。

## 代码结构（实际）

```
com.quantification
├── Application          启动类（@SpringBootApplication + @EnableScheduling + @MapperScan）
├── admin                接口层：CollectController（手动触发采集）、FundingYieldController
├── bitget               交易所客户端
│   ├── BitgetPublicClient   公共接口（行情/费率/盘口/折扣率/交易对规则），含模拟盘变体
│   ├── BitgetPrivateClient  私有接口（账户/持仓/下单/撤单/查单/费率），含签名与模拟盘开关
│   └── HttpRetry            网络抖动重试（只重试 IO 异常，业务错误不重试）
├── entity               实体（与表一一对应）
├── mapper               MyBatis Mapper
└── service              业务层
    ├── WatchCoinService      篮子同步（成交额 + 折扣率 + 模拟盘报价校验）
    ├── InstrumentService     交易对规则同步（精度/最小最大/乘数落库）
    ├── FundingRateService    历史/实时资金费率采集
    ├── FeeRateService        账户真实手续费率采集
    ├── FundingAnalysisService 费率分析（窗口净年化，候选排序）
    ├── FundingYieldService   收益率展示（后台用，支持 /api/admin/funding-yield）
    ├── StrategyService       决策（目标仓位）
    ├── OrderExecutionService 执行（FOK + 成交反查 + 补单/回退）
    ├── RiskService           熔断与风险指标
    └── TradingLoopService    交易循环编排（含决策日志、启动对账）
```

## 数据库

装在本机内置盘，端口 3306 只监听 `127.0.0.1`：库 `quantification_test`（日常开发）、
`quantification_prod`（本机演练），账号 `quant_app`。

**已建 16 张**：`instrument`（交易对规则）、`watch_coin`（篮子 + 折扣率 + 模拟盘/实盘支持标记）、
`funding_rate_history`、`funding_rate_current`、`ticker_snapshot`、`account_balance_snapshot`、
`account_asset_snapshot`、`position_snapshot`、`account_financial_record`、`fee_rate`、
`trade_order`、`trade_fill`、`strategy_position`、`funding_income`、`mock_account`、`mock_position`。
（`pnl_daily` 还没建，等模块 8 盈亏核算落地时再建。）

**迁移**：`V1`（一期建表）、`V2`（篮子种子）、`V3`（折扣率）、`V4`（模拟盘/实盘支持标记）、
`V5`（trade_order、trade_fill、funding_income、mock_account、mock_position、strategy_position）、
`V6`（event_log 事件日志）、`V7`（mock_position 加 spot_avg_price 现货均价）。
**规则**：设计期可改 V1 并重置测试库；**schema 冻结后只加新脚本，不改旧的**。
**每张表的作用见 `docs/database-tables.md`；改任何表结构（加/删/改字段、改唯一键、建表）都必须同步更新它。**

## 关键配置（`src/main/resources/application.yml`）

| 配置段 | 关键项 | 说明 |
| --- | --- | --- |
| `spring.config.import` | `~/.quantification/application-local.yml` | **密钥从这里加载**（仓库外） |
| `bitget` | `paptrading` | true = 模拟盘（用 demo-* 密钥 + `paptrading:1` 头），false = 实盘 |
| `watch-coin` | `min-turnover` 500 万、`min-discount-rate` 0.8 | 目标币种池门槛 |
| `instrument` | 每小时同步交易对规则 | 精度/最小最大/乘数落库 |
| `strategy` | `enabled`、`allow-real-trading`、`entry-net`、`switch-gap`、`max-holdings`、`max-single-weight`、`lookback-days` | 策略参数 |
| `execution` | `price-buffer` 1%、`leg-tolerance` 0.5%、`order-timeout-ms`、`max-retries` | 执行参数 |
| `simulation` | `enabled`、`initial-usdt`、`maintenance-margin-rate`、结算 / 快照间隔 | **自建 mock 开关**：true = 行情读真实接口、下单本地模拟 |
| `risk` | `max-drawdown` 5%、`mgn-ratio-warn/reduce`、标记价偏离阈值 | 风控参数 |
| `alert.mail` | SMTP 配置（**密码放仓库外**） | 告警，未接发送逻辑 |

**两道安全闸（防误下真单）**：
1. `strategy.enabled=false` → 只评估、永不下单
2. 实盘（`paptrading=false`）时还要求 `strategy.allow-real-trading=true`，否则只打印"本应下单"

模式由 `ExchangeGateway` 决定：`simulation.enabled=true` → 自建 mock（不动真实资金，不需要第二道闸）；
否则由 `bitget.paptrading` 选官方模拟盘或实盘。

## 怎么运行

```bash
./mvnw clean test                      # 提交前必须通过
./mvnw spring-boot:run                 # 启动（会自动下单，模拟盘）
./mvnw spring-boot:run -Dspring-boot.run.arguments=--strategy.enabled=false   # 只评估不下单
caffeinate -i ./mvnw spring-boot:run   # 需要整晚跑时，防止 Mac 休眠

# 自建 mock（验证策略收益用：行情读真实接口，下单只在本地模拟）
./mvnw spring-boot:run -Dspring-boot.run.arguments="--simulation.enabled=true"
curl -s localhost:8080/api/admin/simulation/status         # 现金 / 净值 / 持仓 / 资金费 / 滚动年化
curl -s localhost:8080/api/admin/simulation/equity-curve   # 模拟净值曲线
```

启动后的时间线：3s 篮子同步 → 8s 历史费率 → 5s 实时费率 → 10s 交易对规则 → 15s 手续费率 →
**20s 策略首次评估**（打印候选、决策、下单）。

## 模拟盘实测结论（2026-09-23/24）

| 结论 | 细节 |
| --- | --- |
| **支持 API 交易** | 用模拟盘专用 key + 请求头 `paptrading: 1`；公开产品接口加该头也能拿模拟盘清单（无需签名） |
| **生产 key 切不到模拟盘** | 实测两次调用返回同一真实账户，必须用模拟盘 key |
| **覆盖范围很小** | 现货 25 个、合约 45 个，**真实币交集只有 BTC/ETH/DOGE/SOL** |
| **现货报价部分不可信** | ETH 卖一 54500（真实 2726）、SOL 偏离 55%；**BTC/XLM 正常** → 代码已自动识别并排除 |
| **限价有 ±2% 风控** | 限价偏离市价超过 2% 直接被拒（`25206`） |
| **现货默认不计入保证金** | 抵押品模式为 `custom`（仅 USDT/USDC）→ `effEquity` 不含现货；**待确认项，上实盘前必须核实** |
| **真实结算资金费（已确认）** | 2026-09-24 08:00 到账 +0.4749 USDT（类型 `CONTRACT_MAIN_SETTLE_FEE_USER_IN`）→ **模块 9 不需要自己 mock 资金费** |
| **但金额口径不可信** | 实际到账 0.4749，而"名义 18,958 × 费率 0.000068 = 1.289"应得 1.289 —— 差 2.7 倍。**模拟盘的资金费金额不能用来校验策略收益**，收益验证仍需自建 mock |

## 策略口径（回测与代码一致）

- **窗口**：10 天，窗口内费率**正负累加**（不是"连续 10 天为正"）
- **净年化** = 毛年化 − 换仓成本年化；换仓成本 = 2 × (现货吃单 0.06% + 合约吃单 0.0375%) × 365/45 ≈ **1.58%/年**
  （费率取**私有接口的账户真实值**；公共接口给的标准值偏高，不能用）
- **结算周期因币而异**（实测 4h 居多），年化按各币实际周期折算
- **回测结论**（池 ≥500 万、门槛 5%、换仓差 4 个点、最多 3 币）：**年化 11.71%，全程 3 币分散**
- **费率恢复轮数过滤**：回测里有效（+0.44 个点且不牺牲分散度），**策略代码里还没加**

## 文档索引

| 文档 | 内容 |
| --- | --- |
| `docs/phase-2-strategy-design.md` | **二阶段策略设计（10 个模块定稿）**，含盘口滑点实测、风控定稿、模拟盘结论 |
| `docs/troubleshooting.md` | **报错排查与解决方案**（踩过的坑：bash 兼容、Flyway、接口错误码、下单精度、决策过滤、休眠等） |
| `docs/order-handling.md` | **订单与资金处理最佳实践**（幂等、状态机、精度校验、两条腿纪律、对账、低级错误清单） |
| `docs/coding-standards.md` | 编码规范（注释、时间类型、包结构、迁移规则） |
| `docs/data-model.md` | 数据模型 + 全市场扫描结论 |
| `docs/database-tables.md` | **每张表的作用说明书（改表必须同步更新）** |
| `docs/api-endpoints.md` | Bitget UTA 接口梳理 |
| `docs/architecture.md` | 技术选型与架构 |

## 安全红线（铁律）

⚠️ **这个仓库是公开的（public），且 API Key 权限很大、关联资金较多。**

**铁律：密钥在任何场景下都不许进仓库、不许打包、不许提供给外部，只能在本机使用。**

- 密钥（数据库密码、Bitget 的 api-key / secret-key / passphrase、模拟盘 demo-* 密钥、SMTP 密码）
  只放**仓库外**的：`~/.quantification/application-local.yml`（权限 600），由 `spring.config.import` 自动加载
- **永远不要**在 `src/main/resources/` 下建 `application-local.yml`，也不要把密码写进 `application.yml`
- 两道自动防护，**别绕过**（提交不要加 `--no-verify`）：
  - **构建校验**：`pom.xml` 的 exec-maven-plugin 在 validate / package 阶段跑 `scripts/check-secrets.sh`
  - **提交钩子**：`.githooks/pre-commit`（已执行 `git config core.hooksPath .githooks`；**新克隆要再做一次**）
- 整个项目目录可以安全地拷给别人（里面没有密钥）；`~/.quantification/application-local.yml` 是密钥唯一副本，注意备份

## 分支与协作约定

- 主干是 **`master`**（不是 main）
- 流程：从 master 拉 `feature/xxx` → 开发 → 推送 → PR → 合并回 master
- 合并前必须 `./mvnw clean test` 通过
- 远程走 SSH：`git@github.com:xiaochenge/quantification.git`（`~/.ssh/config` 里配了 ssh.github.com:443）
- 和用户协作：**用中文沟通**；动手改代码前先给方案；一次只做一件事；不确定就问
- 用户偏好：**核心交易/策略模块**要仔细确认；**非核心模块**（盈亏核算、模拟、后台）可以直接定，
  但要把决定和理由说清楚

## 待办与遗留问题

1. **当前分支名有拼写错误**：`feautre/init_Requirement_Design` 应为 `feature/init_Requirement_Design`
2. `.idea/` 已加进 .gitignore，但之前提交的 `.idea/*.xml` 仍在版本控制里，需要 `git rm -r --cached .idea`
3. **上实盘前必须核实：实盘账户的抵押品模式**（现货是否计入保证金，影响收益是否要打对折）
4. ~~待验证：模拟盘是否真实结算资金费~~ → **已验证会结算**，但金额口径与标准公式不一致（见"模拟盘实测结论"）
4b. **历史费率采集有最多 6 小时滞后**（每 6 小时拉一次增量）：对 10 天窗口的策略无影响，但后台"最新费率"会滞后，后续可考虑把增量采集频率提高
5. **自建 mock 已落地**（模块 9 v1，见"下一步开发计划"第 1 条）：验证策略收益改走它
5b. **mock 的已知简化**：平仓数量超持仓按"减到零"处理（实盘会拒单）；开空保证金按 1 倍有效权益粗算；
    资金费名义值用结算时刻最新价近似；**结算点数据最多滞后 6 小时，若刚过结算点就平仓可能漏记最后一段资金费**（模块 8 用真实流水核对时补算）
6. 盈亏核算、管理后台、邮件告警、恢复轮数过滤、实盘订单状态机、下架监控：见"未完成"清单
7. **部署方案待定**：打成可执行 jar 用 launchd 托管，还是用 Docker
8. 服务器到位后要处理：关闭自动休眠、配 UPS 防断电、数据库定期备份

## 下一步开发计划（按优先级，新会话可直接开工）

### 1. 自建 mock（模块 9）—— ✅ 已完成（2026-09-24，v1）

**落地形态**（详见 `docs/phase-2-strategy-design.md` 第 10.5 节）：

- `com.quantification.exchange`：`ExchangeGateway` 接口 + `RealExchangeGateway`（实盘 / 官方模拟盘）
  + `MockExchangeGateway`（自建 mock）+ `MatchEngine`（按真实盘口逐档撮合，算成交均价与滑点）
- 开关：`simulation.enabled=true`（`bitget.paptrading` 继续负责区分实盘 / 官方模拟盘，两道安全闸不变）
- 模拟账本：`trade_order` / `trade_fill`（source=mock）、`mock_account` / `mock_position`（现金与持仓，重启可恢复）、
  `funding_income`（资金费自己算，按结算点幂等去重）
- 只读接口：`GET /api/admin/simulation/status`、`GET /api/admin/simulation/equity-curve`；
  另有调试用的 POST `/snapshot`、`/settle-funding`

**已实测**（2026-09-24）：42 币候选池选出 3 个建仓（现货买 + 永续空），按真实盘口成交并记录滑点与手续费；
重启后自动接管 3 组模拟持仓；资金费按真实结算点入账（3 币 × 6 个结算点 = 2.58 USDT/天，对应名义 8.6k，
折算年化约 11%，与 `scripts/backtest-funding.py` 的结论同量级）。

**为什么必须做**：官方模拟盘只覆盖 BTC/ETH/DOGE/SOL，其中 **ETH/SOL 的现货报价是坏的**
（ETH 卖一 54500 而市价 2726），实际只能拿 BTC 跑流程；而且它的**资金费金额口径与标准公式不一致**
（实得 0.4749 vs 应得 1.289，差 2.7 倍）。**要验证策略收益（8% 目标）只能靠自建 mock。**

**做法**（复用一期就定好的"Real / Mock 双实现"）：

- **行情、费率、盘口全部读真实接口**；**下单 / 撤单 / 查单在本地模拟**
- 成交价用**真实盘口深度**推算（算法见 `scripts/orderbook-slippage.py`），并计入滑点与手续费
- 模拟订单/成交写入 `trade_order` / `trade_fill`，`source = mock`
- 资金费按**真实费率 × 仓位**记入 `funding_income`（`source = mock`）——
  这正是模拟盘算错的部分，所以**必须自己算**
- **其余模块（调度、决策、风控、对账、核算）与实盘共用同一套代码**，只替换交易所实现
- 建议加 `strategy.mode: real | demo | mock` 开关（或复用 `bitget.paptrading` + 新增 `simulation.enabled`）

**验收**：能对完整候选池（40+ 币）跑出一段时间的净值曲线与滚动年化，量级与
`scripts/backtest-funding.py` 的结论（≥500 万池、年化 11.71%）大致吻合。

### 2. 盈亏核算（模块 8）

**为什么**：后台看板的数据全靠它，也是判断"策略到底赚了多少"的唯一依据。

**口径（已定稿，详见 `docs/phase-2-strategy-design.md` 9.1）**：

- 净值**双口径**：交易所 `accountEquity`（对账用）+ 自算策略口径（评估用）
- 成本：手续费与滑点**按每笔成交记原始值、按每次换仓汇总**
- 资金费：**以交易所真实流水为准**（`account_financial_record`），理论值只作对照
- 已实现/未实现：以"**一组策略仓位**"为单位结算
- 年化：默认**含成本、滚动 30 天**，另提供 7/90 天与累计
- 归因：单币 + 组合两级；加"闲钱活期"基准线

**验收**：后台接口能返回上述指标；与交易所流水核对能解释每一分钱差异。

### 3. 补齐既有设计的缺口

- **费率恢复轮数过滤**：回测脚本里已实现（`scripts/backtest-funding.py` 的 `precompute_recovery`），
  `FundingAnalysisService` 里还没加。回测显示它 +0.44 个点且不牺牲分散度
- **订单 / 成交落库**：当前靠"查交易所状态"做幂等，没用 `trade_order` / `trade_fill` 表。
  落库后才能做订单状态机、对账留痕、盈亏归因
- （次要）把历史费率的增量采集频率从 6 小时提高，减少后台展示滞后

**验收**：`trade_order` / `trade_fill` / `strategy_position` 有数据；重启后能靠"本地记录 + 交易所对账"恢复状态。

## 写代码前必读

1. `docs/coding-standards.md` —— 注释、时间类型、包结构、迁移规则
2. `docs/order-handling.md` —— **订单与资金相关**：幂等、状态机、精度校验、两条腿纪律
3. `docs/troubleshooting.md` —— **先扫一遍**，避免重复踩坑
4. `docs/database-tables.md` —— **每张表的作用（改表前先看，改完同步更新）**

## 机器与部署规划

**当前这台——开发机**

- Mac mini（Mac16,11），Apple M4 Pro，24GB 内存，512GB SSD，macOS 27
- 环境已配好：JDK 24、Maven 3.9.16（PATH 写在 `~/.zshrc`）、Git 身份 `xiaochenge`
- 按开发机定位使用，不需要 24 小时开机（**需要整晚跑测试时用 `caffeinate -i`**）

**将来那台——服务器**

- 计划再买一台 Mac mini，专门部署正式运行
- 到那时需要：UPS 防断电、关闭自动休眠、数据库备份、开机自启
- 现在不用操心，但写代码时别依赖开发机的专有路径和手工步骤
