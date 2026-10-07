# quantification 项目交接与约定

> 本文件由 Codex 在每次新会话开始时自动读取。**新会话先读这一份，再读 `docs/` 下的文档。**
> **本文档已按 2026-10-04 的实际代码逐项核对；与旧设计文档冲突时，以当前代码与本文档为准。**
> 最后更新：2026-10-04

## 0. 三十秒速览

| 问题 | 答案 |
| --- | --- |
| 做什么 | Bitget 资金费率套现：**现货多 + 永续空（delta 中性）**吃资金费，按费率变化动态调仓 |
| 做到哪了 | **一期（数据/策略/执行/风控/对账）全部完成**；自建 mock（模块 9）、管理后台（模块 10）、邮件告警、恢复轮数过滤都已落地 |
| 现在在跑什么 | **自建 mock 长期验证**（初始 **3 万 USDT**），由 launchd 托管、防休眠，计划跑 3 个月 |
| 用哪个模式 | **只走自建 mock**（`simulation.enabled=true`）。**Bitget 官方模拟盘（demo 账户）已弃用、不再使用**；实盘尚未启用 |
| 下一步做什么 | **改「下单 / 换仓」逻辑**（见第 15 节）；以及模块 8 完整盈亏核算 |

## 1. 项目是什么

Bitget 单交易所的资金费率套现策略：同时监控多个币种，对"10 天窗口净年化"达标的币建立
**现货多 + 永续空**的 delta 中性仓位吃资金费，按费率变化动态调仓。

- 目标：**理想总体年化 8%**（弹性目标，非保底）；铁律是**宁可空仓，也不亏钱**
- 账户类型：Bitget **统一账户（UTA）**，走 `/api/v3`
- 权威设计文档：`docs/` 下的设计文档（但**以本文件与代码为准**）

## 2. 当前状态（里程碑 2026-10-04）

### ✅ 已完成

| 部分 | 内容 |
| --- | --- |
| **数据层（模块 1、2）** | Flyway `V1`~`V8`，**18 张业务表**；自建 Bitget 客户端（公共 + 私有，签名 / 模拟盘开关 / 网络重试） |
| **采集** | 篮子自动同步（成交额 ≥500 万 + 折扣率 ≥0.8）、历史费率（**每 1 小时**增量补采）、实时费率（每 10 分钟）、交易对规则（每小时）、真实手续费率（每天，非 mock） |
| **策略（模块 3、4）** | 10 天窗口净年化排序、**恢复轮数过滤**（已实现）、目标仓位与权重（成交额加权、单币 ≤40%、总投入 ≤95%） |
| **执行（模块 5）** | FOK 限价单 + 盘口深度定量 + **成交反查** + 补单 / 回退（两腿纪律） |
| **风控（模块 6）** | 回撤熔断、保证金率预警/减仓、**熔断分级**（网络类异常不熔断只重试）、邮件告警 |
| **对账（模块 7）** | 启动时以交易所为准接管持仓；**孤儿仓位**（掉出篮子但仍持仓）会被自动平掉 |
| **自建 mock（模块 9）** | `ExchangeGateway` 双实现；按真实盘口撮合、记滑点与手续费；模拟账本落库；**资金费自己按真实费率×真实仓位结算**；平仓前补拉费率防漏结 |
| **管理后台（模块 10）** | 免登录只读看板（Vue3 + Element Plus + ECharts，由 Spring Boot 静态托管），7 个页面 + 机器监控 |
| **事件留痕** | `event_log` 表记录熔断 / 邮件 / 下单失败 / 对账接管 |
| **安全加固** | 密钥在仓库外 `~/.quantification/application-local.yml`；构建 + 提交双重校验 |

### 🔄 当前在跑

- **模式**：自建 mock（`simulation.enabled=true`）。
- **资金**：初始 3 万 USDT（`simulation.initial-usdt`），第一期拟投入规模。
- **托管**：launchd（`~/Library/LaunchAgents/com.quantification.app.plist`）：开机自启 + 崩溃自动重启 + 日志落 `logs/app.out.log`。
- **防休眠**：`com.quantification.nosleep`（caffeinate -i -s），Mac 不会休眠导致程序冻结。
- **数据库**：MySQL 由 `com.quantification.mysql` 托管，开机自启。
- 长期目标：连续跑 **≥3 个月**、滚动年化 ≥8%、最大回撤 ≤5%。

### ⏳ 未完成

| 项 | 说明 |
| --- | --- |
| **盈亏核算（模块 8）** | 只做了**最小版**：`/api/admin/pnl` 给出"资金费 − 手续费 + 现货/永续已实现 + 未实现 + 残差"的分解。完整口径（按"一组策略仓位"结算、滚动 7/30/90 天年化、单币+组合归因、闲钱活期基准）**未做** |
| `strategy_position` 表 | 已建表但**还没写入**（属于模块 8） |
| 实盘路径的订单落库 | `trade_order` / `trade_fill` 目前只由 mock 写；实盘路径还没接"反查订单 → 更新本地状态机" |
| 下架监控 | 监听公告、持仓币种下架时平仓（一期人工） |
| 若干配置项未接线 | 见第 11 节 |

## 3. 运行模式与交易所接入（最重要的一节）

### 3.1 三种模式 `TradeMode`

代码位置：`exchange/TradeMode.java`、`exchange/ExchangeGateway.java`。

| 模式 | 说明 | 当前是否使用 |
| --- | --- | --- |
| `REAL` | 实盘，真钱真单；需 `strategy.allow-real-trading=true` 这道闸 | ❌ 未启用 |
| `DEMO` | **Bitget 官方模拟盘**（`bitget.paptrading=true` + demo 密钥 + `paptrading:1` 头） | ❌ **已弃用** |
| `MOCK` | **自建 mock**（`simulation.enabled=true`）：行情读真实接口、下单本地模拟 | ✅ **当前模式** |

### 3.2 为什么弃用官方模拟盘（DEMO）

实测结论（2026-09-23/24）：

- **覆盖太小**：现货 25 个、合约 45 个，真实币交集只有 **BTC/ETH/DOGE/SOL**；
- **现货报价部分是坏的**：ETH 卖一 54500（真实 2726）、SOL 偏离 55% → 代码会自动识别并标记为模拟盘不可交易；
- **资金费金额口径不可信**：2026-09-24 08:00 实际到账 +0.4749，而按"名义 18958 × 费率 0.000068"应得 1.289，差 **2.7 倍**；
- 另有 **±2% 限价风控**（偏离市价超 2% 直接拒单，`25206`）。

结论：官方模拟盘只能验证"程序链路"，**不能验证策略收益**。收益验证改由自建 mock 承担。
代码里 `RealExchangeGateway`（含 DEMO）仍然保留，但**不再使用**。

### 3.3 自建 mock 的行为（`exchange/MockExchangeGateway`）

- **读真实**：行情 / 费率 / 盘口 / 折扣率 / 交易对规则全部走 Bitget 公共接口（`BitgetPublicClient`）；该客户端**已接网络重试**（`HttpRetry`，只重试 IO 类异常）。
- **本地撮合**：`MatchEngine` 从真实订单簿逐档吃单算成交均价与滑点；限价 FOK 吃不下就整笔撤销；精度 / 余额 / 保证金不足一律拒单（模拟 `40808` / `25202`）。
- **本地账本**：现金存 `mock_account`、持仓存 `mock_position`（**重启从表恢复**，不重置收益）。
- **资金费自己算**：真实费率 × 真实仓位 → `funding_income`（唯一键 `(source, symbol, settlement_time)` 幂等，重启不重复入账）；**平仓前会补拉该币最近费率**，避免"币掉出篮子后最后一段结算点没采到"导致漏结。
- **成交落库**：`trade_order` + `trade_fill`（`source=mock`），含滑点、手续费、两条腿的已实现盈亏。
- **安全边界**：该类**不注入** `BitgetPrivateClient`，从设计上不可能发出真实交易请求。
- **与实盘有意的差异**（简化）：① 平仓数量超持仓按"减到零"处理（实盘会拒单）；② 开空保证金按 1 倍有效担保粗算；③ 维持保证金率用 `simulation.maintenance-margin-rate` 固定假设；④ 资金费名义值用结算时刻最新价近似。

### 3.4 两道安全闸（防误下真单）

1. `strategy.enabled=false` → 只评估、永不下单；
2. `strategy.allow-real-trading=false`（默认）→ 即使模式是 `REAL`，也只在日志里打印"本应下单"，不下单。

模式是 `MOCK` 或 `DEMO` 时不需要第二道闸（本来就不动真实资金）。

## 4. 怎么运行 / 运维

### 4.1 打包与启动

```bash
./mvnw clean test                      # 提交前必须通过（13 个单测）
./mvnw -DskipTests package             # 打成可执行 jar：target/quantification-1.0-SNAPSHOT.jar
```

**正式（当前用法，launchd 托管，开机自启 + 崩溃自愈）**：

```bash
cp deploy/com.quantification.app.plist ~/Library/LaunchAgents/          # 路径按机器改
launchctl load  ~/Library/LaunchAgents/com.quantification.app.plist
launchctl unload ~/Library/LaunchAgents/com.quantification.app.plist    # 停止
launchctl list | grep quantification                                    # 看状态
tail -f logs/app.out.log logs/app.err.log                               # 看日志
```

**临时前台跑**：`./mvnw spring-boot:run`。
**改了代码后要让改动生效**：重新 `package` → `launchctl unload` + `load`（重启后 mock 状态从数据库恢复，不丢数据）。

### 4.2 管理后台

浏览器打开 **`http://localhost:8080`**（免登录、只读）。静态页由 Spring Boot 托管
（`src/main/resources/static/`），**已配置 `Cache-Control: no-store`**，改前端后浏览器不会吃到旧缓存。

### 4.3 远程运维（Windows → Mac）

已配好：**Tailscale**（Mac 的地址形如 `100.69.19.17`）+ macOS 自带**屏幕共享**（VNC，5900）+ **远程登录**（SSH，22）。

- Windows 侧装 Tailscale（同账号）+ 任意 VNC 客户端（RealVNC Viewer / TightVNC），连 `100.x.x.x:5900`；
- 或直接 `ssh chenwu@100.x.x.x` 做命令行运维；
- **建议配一个 HDMI 虚拟显示器诱骗器**（dummy plug），否则无头 Mac 的远程桌面分辨率会很低。

### 4.4 启动后的时间线

3s 篮子同步 → 5s 实时费率 → 8s 历史费率 → 10s 交易对规则 → 15s 费率采集 →
20s 策略首次评估 + 主机监控首次采样 → 之后每小时评估 + 每小时净值快照、每 1 分钟主机采样、
每 1 分钟资金费结算扫描。

## 5. 技术栈

| 项目 | 实际使用 |
| --- | --- |
| JDK | **24**（`~/Library/Java/JavaVirtualMachines/openjdk-24.0.2+12-54`，launchd 里用绝对路径） |
| 构建 | Maven 3.9.16，项目自带 `./mvnw` |
| 框架 | **Spring Boot 4.1.1**（`spring-boot-starter-web` / `-flyway` / **`-mail`** / `-test`） |
| 持久层 | **MyBatis 4.1.0**（原生 Mapper，注解 SQL，`map-underscore-to-camel-case`） |
| 数据库 | **MySQL 8.4.11**（`~/Library/MySQL`，只监听 `127.0.0.1:3306`），库 `quantification_test` / `quantification_prod`，账号 `quant_app` |
| 结构迁移 | **Flyway 12.4.0**（`V1`~`V8`） |
| JSON | Jackson 3（databind 在 `tools.jackson`，注解仍是 `com.fasterxml.jackson.annotation`） |
| 交易所接入 | **自建轻量客户端**（不引官方 SDK：未发布到 Maven 且 Java 8 老依赖，JDK 24 编译不了） |
| 前端 | **Vue 3 + Element Plus + ECharts**，CDN 引入、**免构建**（无 node 依赖），静态托管 |
| 主机监控 | JDK `com.sun.management.OperatingSystemMXBean`（CPU/内存）+ `File` 容量（磁盘）+ `sysctl vm.swapusage`（macOS 交换区） |

## 6. 代码结构（实际）

```
com.quantification
├── Application                启动类（@SpringBootApplication + @EnableScheduling + @MapperScan）
├── admin                      只读接口层
│   ├── DashboardController        后台看板接口（见第 9 节）
│   ├── SimulationController       模拟盘查询/调试（/api/admin/simulation/*）
│   ├── CollectController          手动触发采集（/api/admin/collect/*）
│   ├── FundingYieldController     收益率展示（/api/admin/funding-yield）
│   └── AlertController            发测试告警邮件（/api/admin/alert/test）
├── bitget                     交易所底层客户端
│   ├── BitgetPublicClient         公共接口（行情/费率/盘口/折扣率/交易对规则），含模拟盘变体，**带 HttpRetry**
│   ├── BitgetPrivateClient        私有接口（账户/持仓/下单/撤单/查单/费率），含签名与模拟盘开关
│   ├── BitgetApiException         业务异常
│   └── HttpRetry                  网络抖动重试（只重试 IO 异常）
├── exchange                   **交易所网关层（模式隔离）**
│   ├── ExchangeGateway            接口：assets / positions / 下单 / 撤单 / 查单 / 费率 / settleFunding
│   ├── RealExchangeGateway        实盘 + 官方模拟盘实现（转发 BitgetPrivateClient；当前未用）
│   ├── MockExchangeGateway        自建 mock（本地撮合 + 账本 + 资金费结算）★当前模式
│   ├── MatchEngine                按真实盘口逐档撮合的纯算法（有单测）
│   └── TradeMode                  REAL / DEMO / MOCK
├── entity                     实体（与表一一对应）
├── mapper                     MyBatis Mapper
└── service                    业务层
    ├── WatchCoinService           篮子同步（成交额 + 折扣率 + 模拟盘报价校验）
    ├── InstrumentService          交易对规则同步（精度/最小最大/乘数落库）
    ├── FundingRateService         历史/实时资金费率采集（历史增量"碰到已有即停"，不断档）
    ├── FeeRateService             账户真实手续费率采集（**mock 模式跳过**）
    ├── FundingAnalysisService     费率分析：10 天窗口净年化 + **恢复轮数过滤**
    ├── FundingYieldService        收益率展示（90 天窗口，给后台"候选池"用）
    ├── StrategyService            决策：候选 → 目标仓位（含恢复过滤、权重、单币上限）
    ├── OrderExecutionService      执行：盘口定量 + FOK + 成交反查 + 补单/回退
    ├── RiskService                熔断与风险指标（熔断会落 event_log + 发邮件）
    ├── TradingLoopService         交易循环编排（决策日志、启动对账、孤儿仓位平仓）
    ├── SimulationService          模拟盘账务：资金费结算调度、净值快照、查询
    ├── DashboardService           后台数据装配（总览/持仓/候选池/参数/盈亏/事件）
    ├── HostMonitorService         主机资源采样（CPU/内存/磁盘/负载/交换区）
    ├── EventLogService            事件落库（熔断/邮件/下单失败/对账）
    └── MailAlertService           邮件告警（SMTP，失败不抛异常、按主题节流）
```

## 7. 数据库（18 张表 · Flyway V8）

**迁移**：`V1`（一期 10 张表）、`V2`（篮子种子）、`V3`（watch_coin 加折扣率）、
`V4`（watch_coin 加实盘/模拟盘支持标记）、
`V5`（trade_order、trade_fill、funding_income、mock_account、mock_position、strategy_position）、
`V6`（event_log）、`V7`（mock_position 加 spot_avg_price）、`V8`（host_metric_snapshot）。
**规则**：设计期可改 `V1` 并重置测试库；**schema 冻结后只加新脚本，不改旧的**。

**18 张表**：`instrument`、`watch_coin`、`funding_rate_history`、`funding_rate_current`、
`ticker_snapshot`、`account_balance_snapshot`、`account_asset_snapshot`、`position_snapshot`、
`account_financial_record`、`fee_rate`、`trade_order`、`trade_fill`、`funding_income`、
`mock_account`、`mock_position`、`strategy_position`、`event_log`、`host_metric_snapshot`。

> **每张表的作用、谁写谁读、关键字段，见 `docs/database-tables.md`。改任何表结构必须同步更新它。**

当前实际有数据的是：`funding_rate_history`（约 15.6 万行）、`funding_rate_current`、`instrument`、
`watch_coin`，以及 mock 运行产生的 `mock_account` / `mock_position` / `trade_order` / `trade_fill` /
`funding_income` / `account_balance_snapshot(source=mock)` / `event_log` / `host_metric_snapshot`。
`ticker_snapshot` / `account_asset_snapshot` / `position_snapshot` / `account_financial_record` /
`strategy_position` 目前是**空表**（对应采集/核算尚未实现）。

## 8. 关键配置（`src/main/resources/application.yml`）

| 配置段 | 关键项 | 说明 |
| --- | --- | --- |
| `spring.config.import` | `~/.quantification/application-local.yml` | **密钥从这里加载**（仓库外） |
| `spring.web.resources.cache` | `no-store: true` | 后台静态页不缓存 |
| `bitget` | `paptrading: false`、`connect-timeout-ms: 5000`、`read-timeout-ms: 10000` | `paptrading` 当前用不到（mock 模式不走私有客户端）；**HTTP 超时是 2026-10-07 加的**，防"对端连接半开 → 本地读永久挂起 → 占死锁 / 请求线程"这类故障 |
| `watch-coin` | `min-turnover: 20000000`、`min-discount-rate: 0.8` | 目标币种池门槛。**成交额 2026-10-07 从 500 万提到 2000 万**（实测薄盘币买卖价差极宽，PUMP 建仓时腿间价差吃到 0.28%）；提到 2000 万后篮子从 33 个降到 14 个 |
| `strategy` | `enabled`、`allow-real-trading`、`entry-net: 0.05`、`max-holdings: 3`、`max-single-weight: 0.40`、`target-invest-ratio: 0.95`、`lookback-days: 10`、`recovery-filter: true`、`switch-gap: 0.10`、`min-holding-days: 7` | 策略参数。**`switch-gap` 2026-10-07 从 4 个点提到 10 个点、并新增 `min-holding-days: 7`**（理由见第 11 节） |
| `execution` | `price-buffer`、`leg-tolerance`、`slippage-limit-major/small`、`major-turnover`、`min-order-notional`、`depth-limit` | 执行参数 |
| `simulation` | `enabled: true`、`initial-usdt: 30000`、`maintenance-margin-rate: 0.02`、`depth-limit`、`ticker-cache-ms`、`rolling-days: 30`、资金费结算/快照间隔 | **自建 mock 开关与参数** |
| `host-monitor` | `sample-interval-ms: 60000`、`disk-path: /` | 主机监控采样 |
| `collector` | `history-interval-ms: 3600000`（**已从 6h 调到 1h**）、`current-interval-ms: 600000`、`fee-rate-*` | 采集节奏 |
| `risk` | `max-drawdown: 0.05`、`mgn-ratio-warn: 0.5`、`mgn-ratio-reduce: 0.8` | 风控阈值 |
| `alert.mail` | `enabled: true`、`host: smtp.qq.com`、`port: 465`、`username/to: 421791582@qq.com`、`cooldown-seconds: 300` | 邮件告警（**密码在仓库外**） |
| `alert.transient-failures-before-warning` | `6` | 连续多少次瞬时网络失败后发提醒（不熔断） |
| `alert.watchdog` | `enabled: true`、`check-interval-ms: 300000`、`stale-ms: 9000000`、`remind-ms: 3600000` | **看门狗（2026-10-07 新增）**：专盯"程序还活着但什么都不干"的沉默型故障——策略循环每跑完一轮打一次心跳，超过 2.5 小时没心跳就发邮件（一直没恢复每小时再提醒，恢复后补一封恢复通知）。用独立守护线程，不占 Spring 定时任务线程池 |

## 9. 管理后台（模块 10）

**形态**：Spring Boot 托管静态页（`static/index.html` + `static/app.js`），Vue3 + Element Plus + ECharts
全部走 CDN，**免构建、免登录、只读**，浏览器打开 `http://localhost:8080`。前端 10s / 30s 轮询。

**7 个页面**：总览（含盈亏核算卡片）、持仓、候选池（含"全历史净年化"列、单币费率曲线）、
成交与订单、收益曲线、**机器监控**、异常与告警、当前参数（带中文名称与说明）。

**接口**（全部 GET，前缀 `/api/admin`）：

| 接口 | 内容 |
| --- | --- |
| `/overview` | 模式 / 策略状态 / 权益 / 保证金率 / 资金费 / 手续费 / 年化 / 持仓数 |
| `/positions` | 每个持仓币的两腿数量、均价、最新价、未实现盈亏、累计资金费、累计手续费 |
| `/candidates` | 篮子币的 90 天净/毛年化、成本年化、折扣率、24h 成交额、**全历史净年化** |
| `/params` | 当前参数（带中文名称、说明；数值已去掉科学计数法） |
| `/events` | 事件日志（熔断/邮件/下单失败/对账） |
| `/funding-rate-history?symbol=` | 单币历史费率（画曲线） |
| `/orders`、`/fills`、`/funding-income`、`/equity-curve` | 订单 / 成交 / 资金费 / 净值曲线（source=mock） |
| `/pnl` | 盈亏分解（资金费 − 手续费 + 现货/永续已实现 + 未实现 + 校验残差） |
| `/host`、`/host-history` | 主机当前资源 / 历史曲线 |

调试用入口：`/api/admin/simulation/snapshot`、`/settle-funding`（手动触发结算/快照）、
`/api/admin/collect/*`（手动触发采集）、`/api/admin/alert/test`（发测试告警邮件）。

## 10. 策略与执行口径（当前代码实际行为）

### 10.1 选币与建仓（`FundingAnalysisService` + `StrategyService`）

1. **窗口**：10 天（`strategy.lookback-days`），窗口内每次结算的费率**正负累加**取平均；
2. **毛年化** = 平均每期费率 ×（365×24 ÷ 该币实际结算周期 h）；**净年化 = 毛年化 − 换仓成本年化**；
   换仓成本 = 2 × (现货 0.06% + 合约 0.0375%) × 365/45 ≈ **1.58%/年**；
3. **可交易**：必须在本轮**篮子**里（`watch_coin.enabled=1`：现货+永续都有、24h 成交额 ≥2000 万、折扣率 ≥0.8）；
4. **恢复轮数过滤**：要求"当前连续为正轮数 > 该币历史从负恢复到正的平均轮数"——**只卡新进**，不卡已持仓；
5. **建仓门槛**：净年化 ≥ `strategy.entry-net`（当前 **0.05**），不达标宁可空仓；
6. **目标名单**（2026-10-07 起的换仓纪律，见 `StrategyService.decideTarget`）：
   ① 还拿得住的持仓先保住（只保留净年化最高的 `max-holdings` 个）；
   ② 有空位就补最好的新币（加仓，不需门槛）；
   ③ 满仓后新币必须比"最差且已满 `min-holding-days` 的持仓"高出 `switch-gap` 才换。
   然后 **按 24h 成交额加权**，单币 ≤40%，总投入 ≤95%。
   掉出篮子 / 净年化跌破 `entry-net` 的持仓**不受滞回与最短持有期保护**，照常强制平仓。

> 注意：**当前策略不使用"当前实时费率"选币**，用的是 10 天窗口；单日冲高不作为依据。
> `switch-gap` 滞回与最短持有期已实现（见第 11 节）；**还没有实现"只调权重不换腿"的再平衡**。

### 10.2 执行（`OrderExecutionService`）

- 下单前取**真实盘口**，算"滑点上限内能吃多少"（主流币 0.05% / 小币 0.15%），超出则与目标取小；
- **两条腿同时发起**，FOK 限价单（限价 = 对手价 ± 1% 缓冲），要么全成要么全不成；
- 下单后**必须反查实际成交量**（`order-info` 按 clientOid），一腿没成就用更宽缓冲补；
- 补不上就**回退已成交的那条腿**（兜底方向永远减仓，绝不加仓追平）；真有残留敞口 → 熔断 + 邮件；
- 数量一律**向下取整**到交易所精度（绝不四舍五入），规则从 `instrument` 表读；
- 单笔最小名义 `min-order-notional=20` USDT。

### 10.3 循环（`TradingLoopService`）

- 每小时评估一次；评估异常**分级**：网络类（SSL/连接重置/超时）只 WARN + 下周期重试，
  业务/未知异常才熔断（熔断 → `event_log` + 邮件）；连续 6 次瞬时失败发一封提醒；
- 决策：先平掉"不在目标里"的仓位（含**已掉出篮子的孤儿仓位**），再建新仓；
- **孤儿仓位**：币掉出篮子后仍持仓 → 会被 `currentWeights` 看见并按平仓处理
  （曾经因为"只遍历篮子"而看不见、资金被锁死，已修）。

## 11. 已知限制 / 配置项未接线（重要）

**配置里写了但代码没有使用的**（新会话别以为已经生效）：

| 配置项 | 现状 |
| --- | --- |
| `strategy.switch-gap` + `strategy.min-holding-days` | **已实现（2026-10-07）**——`StrategyService.decideTarget` 的三段纪律：① 还拿得住的持仓先保住（只保留最好的 `max-holdings` 个）；② 有空位就补最好的新币（加仓，不需门槛）；③ 满仓后新币必须比"最差**且已满 `min-holding-days`** 的持仓"高出 `switch-gap` 才换。**换仓成本的实测换算（务必记住）：每交易 1 USDT 名义额摩擦成本 ≈ 0.0953%（手续费 0.0487% + 买卖价差/基差 0.0466%），一次完整换仓（平旧+开新）≈ 持仓名义额的 0.19%**；所以 4 个点要持有约 17 天才回本。现取 `switch-gap: 0.10`（≈7 天回本）+ `min-holding-days: 7` 配套 |
| `execution.order-timeout-ms`、`execution.max-retries` | **未使用**（执行走的是 FOK + 成交反查，没有超时重下） |
| `risk.position-watch-interval-ms`、`risk.mark-index-deviation-*` | **未使用**——设计里的"每 5 秒轮询爆仓/ADL"和"标记价偏离"都还没实现 |
| `risk.max-drawdown` | 已用于熔断判断，但**没有实现"回撤触发即全平"**（只停新仓） |
| `fee_rate` 表 | mock 模式**不写**（费率是配置常量）；表为空 |

其他固有限制：

- mock 的简化项见 3.3；
- **最短持有期只在 mock 生效**：`ExchangeGateway.positionOpenedAt()` 默认返回空 Map，
  实盘 / 官方模拟盘拿不到开仓时间，此时该纪律自动不生效（拿不到就不限制，避免把仓位永久锁死）；
  将来做实盘要把开仓时间补上；
- **资金费有滞后**：结算点数据来自历史费率（每 1 小时采一次），资金费入账最多滞后约 1 小时；
- **可能漏结**：若某币掉出篮子前最后一段结算点始终没采到会漏（已在平仓前加"补拉费率"缓解）。

## 12. 安全红线（铁律）

⚠️ 仓库是**公开的**，且 API Key 权限大、关联资金。

- 密钥（DB 密码、Bitget api-key/secret-key/passphrase、demo 密钥、SMTP 密码）**只能**放
  `~/.quantification/application-local.yml`（权限 600，仓库外）。
- **永远不要**在 `src/main/resources/` 下建 `application-local.yml`，也不要把密码写进 `application.yml`。
- 两道自动防护，**别绕过**（提交不要加 `--no-verify`）：
  构建校验（`scripts/check-secrets.sh`，validate/package 阶段跑）+ 提交钩子（`.githooks/pre-commit`）。
- ⚠️ **历史事故（务必阅读）**：2026-10-04 会话中，一次掩码写错的命令把
  **数据库密码、Bitget 实盘/模拟盘密钥、QQ 授权码**打印进了工具输出（即对话上下文）。
  已建议**轮换实盘 API Key**。**新会话读密钥文件时只读"键名"、不要打印值**，
  或用 `awk -F'"' '/password:/{print $2}'` 这类只取单向值、绝不 echo 的写法。

## 13. 分支与协作约定

- 主干是 **`master`**；流程：`feature/xxx` → 推送 → PR → 合并 。
- 当前分支已从拼写错误的 `feautre/...` 改名为 **`feature/init_Requirement_Design`（本地）**；
  远端仍是旧名，需要时 `git push -u origin feature/init_Requirement_Design` 再删除旧远端分支。
- `.idea` 已 `git rm --cached` 移出版本控制（本地文件仍在）。
- **提交现状（2026-10-04）**：最新提交 `c8fccce 完成资金费率初版，项目运行` 已包含自建 mock、
  管理后台、邮件告警、事件表、`deploy/` 的 launchd plist、`.idea` 移出版本控制、以及全部文档。
  **尚未提交**的是最近这一批：**主机监控**（`V8` 迁移 + `HostMetric` / `HostMetricMapper` /
  `HostMonitorService` / 后台"机器监控"页）+ 静态页 `no-store` 缓存配置 + 参数页中文说明 /
  去科学计数法 + 候选池"全历史净年化"列。**新会话动代码前先 `git status` 确认。**
- 和用户协作：**用中文沟通**；**动手改代码前先给方案**；一次只做一件事；不确定就问。
- 用户偏好：**核心交易/策略模块**要仔细确认；非核心模块可直接定，但要说清决定与理由。

## 14. 待办与遗留

1. **下单 / 换仓逻辑**（下一阶段重点，见第 15 节）；
2. 模块 8 完整盈亏核算（按"一组仓位"结算、滚动年化、归因、基准）；
3. `strategy.switch-gap` 滞回**已实现（2026-10-07）**（见第 11 节）；剩下的相关缺口是"只调权重不换腿"的再平衡，以及门槛取值是否匹配实际持有期（实测 4 个点≈17 天回本，而实际持仓只有 1~3 天）；
4. 实盘路径的订单落库与状态机；
5. `risk` 的 5 秒持仓轮询 / 标记价偏离未实现；
6. 下架公告监控；
7. 部署细节：launchd 已托管，将来服务器到位后要处理关闭休眠、UPS、数据库定期备份；
8. 历史费率增量采集现已 1 小时一次，若后台仍嫌滞后可再调。

## 15. 下一步开发计划（新会话从这里开始）

### 1. 下单 / 换仓逻辑（最高优先级）

涉及代码：

- `service/StrategyService.java` —— 目标仓位（**目前只做门槛 + 取前 N，没有滞回**）；
- `service/OrderExecutionService.java` —— 建仓 / 平仓的 FOK 两腿、补单、回退；
- `service/TradingLoopService.java` —— 评估 → 平不在目标里的 → 建新仓；
- `exchange/MockExchangeGateway.java` + `exchange/MatchEngine.java` —— mock 撮合与拒单规则
  （改下单逻辑时要同步考虑模拟是否还原真实行为）；
- `docs/order-handling.md` —— 订单与资金纪律（改之前必读）。

改动要点提醒：

- 换仓是"平掉不在目标里的、建目标里的"；**`switch-gap` 滞回已于 2026-10-07 实现**
  （保留持仓 → 补空位 → 满仓才比价换），但**还没有"只调权重不换腿"的再平衡**；
- 每次换仓的成本都是真实的（手续费 + 基差/滑点），改完要用 mock 观察 `/api/admin/pnl`
  的分解是否合理；
- 改完必须 `./mvnw clean test` 通过，并重新 `package` + 重启 launchd 服务。

### 2. 模块 8 盈亏核算（完整版）

口径见 `docs/phase-2-strategy-design.md` 9.1：净值双口径、成本按笔记/按组汇总、
资金费以交易所流水为准、按"一组策略仓位"结算、滚动 7/30/90 天 + 累计、单币 + 组合归因、闲钱基准。
现在的 `/api/admin/pnl` 只是最小版。

### 3. 补齐缺口

实盘订单落库、`risk` 的轮询与标记价偏离（见第 11 节）；以及"只调权重不换腿"的再平衡。

## 16. 写代码前必读

1. `docs/coding-standards.md` —— 注释、时间类型、包结构、迁移规则；
2. `docs/order-handling.md` —— **订单与资金**：幂等、状态机、精度、两条腿纪律；
3. `docs/troubleshooting.md` —— 踩过的坑（bash 兼容、Flyway、接口错误码、下单精度、休眠、缓存）；
4. `docs/database-tables.md` —— **18 张表的作用（改表必须同步更新）**；
5. `docs/phase-2-strategy-design.md` —— 二阶段设计（模块 3~10），但**以本文件与代码为准**；
6. `docs/data-model.md`、`docs/api-endpoints.md`、`docs/architecture.md` —— 参考。

## 17. 机器与部署规划

**当前这台（已当服务器长期运行）**：Mac mini（Mac16,11，Apple M4 Pro，24GB，512GB）。

- 已配：JDK 24、MySQL 8.4.11、launchd 托管（MySQL / 应用 / 防休眠）、Tailscale + 屏幕共享 + SSH。
- 负载特征：应用进程占用很小（JVM 堆几十 MB），CPU 大部分时间空闲（每小时评估一次）；
  内存"使用率"接近 100% 是 macOS 把空闲内存当文件缓存，**不是异常**，真正要看"交换区是否增长"。
- 建议常备：**HDMI 虚拟显示器诱骗器**（无头远程桌面分辨率）。

**将来**：如需更强的机器再考虑；当前配置对项目负载绰绰有余。
