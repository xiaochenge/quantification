# 数据表说明书（Table Dictionary）

> 这是本仓库**每张数据库表的作用说明书**，给人和 AI 一起查。
> **规则（重要）：每次改表结构（新建表、加/删/改字段、改唯一键、改注释），
> 都必须同步更新本文档；同时更新 `AGENTS.md` 的"表清单"一行。**
> 最后更新：2026-10-04

## 0. 总览

当前共 **16 张业务表**（另有 Flyway 自带的 `flyway_schema_history`，不算业务表）。
按性质分四类：

| 类别 | 表 | 一句话作用 |
| --- | --- | --- |
| 元数据 | `instrument` | 每个交易对的规格字典（精度/最小量/乘数），下单前必查 |
| 元数据 | `watch_coin` | 监控篮子（哪些币可以做，含折扣率、实盘/模拟盘支持标记） |
| 费率 | `funding_rate_history` | 每个结算点的历史资金费率（策略的原材料） |
| 费率 | `funding_rate_current` | 每个币的最新实时费率 + 结算周期 + 下次结算时间 |
| 行情 | `ticker_snapshot` | 事件时的行情快照（最新价/买卖一/标记价/成交额） |
| 账户持仓 | `account_balance_snapshot` | 账户总权益快照（净值曲线来源） |
| 账户持仓 | `account_asset_snapshot` | 账户分币种资产快照 |
| 账户持仓 | `position_snapshot` | 合约持仓快照（交易所视角） |
| 事件 | `account_financial_record` | 账户资金流水（资金费真实到账的权威来源） |
| 事件 | `fee_rate` | 手续费率快照 |
| 交易 | `trade_order` | 订单账本 + 状态机（幂等靠 client_oid） |
| 交易 | `trade_fill` | 成交明细（成本与盈亏的原始依据） |
| 策略 | `funding_income` | 资金费逐笔入账（mock 自算 / real 流水核对） |
| 模拟 | `mock_account` | 自建 mock 的账户现金（单行表） |
| 模拟 | `mock_position` | 自建 mock 的持仓（重启恢复用） |
| 策略 | `strategy_position` | 一组"现货多 + 永续空"对冲组合（业务视角） |

通用约定（详见 `docs/coding-standards.md`）：时间用 `DATETIME`（Asia/Shanghai，不用毫秒时间戳）；
金额/数量用 `DECIMAL(38,18)`；`created_at` / `updated_at` 由数据库自动维护；每个字段都写 `COMMENT`。

---

## 1. 元数据表

### 1.1 `instrument` — 交易对规则

- **作用**：Bitget 上每个交易对的静态规格字典（现货、合约各一行）。下单量必须满足
  `min_order_qty`、是 `quantity_multiplier` 的整数倍、价格符合 `price_precision`，否则被拒单。
  算目标仓位、拆单、模拟撮合都要用这些精度/乘数。
- **数据来源**：`/api/v3/market/instruments`（SPOT 和 USDT-FUTURES 各拉一次）。
- **谁写**：`InstrumentService`（启动 + 每小时同步，只同步篮子里的币）。
- **谁读**：`OrderExecutionService`（下单前校验）、`MockExchangeGateway`（模拟拒单规则）。
- **更新方式**：按 `(category, symbol)` 覆盖写（`upsert`）；**只增不删**（币掉出篮子后旧规则仍在，
  这是为了还能平掉旧仓位）。
- **唯一键**：`(category, symbol)`。
- **关键字段**：`category`、`symbol`、`base_coin`、`quote_coin`、`min_order_qty`、`max_order_qty`、
  `price_precision`、`quantity_precision`、`price_multiplier`、`quantity_multiplier`。

### 1.2 `watch_coin` — 监控篮子

- **作用**：我们"主动选出来要做"的币种清单。一个币对应现货 + 永续两个交易对。
  `enabled` 是开关；`real_supported` / `demo_supported` 标记它在实盘/官方模拟盘能不能交易。
- **数据来源**：`WatchCoinService` 自动同步（启动 + 每天一次）。
- **谁写**：`WatchCoinService`（重建式：先把全部置为停用，再把达标的重新启用）。
- **谁读**：`FundingRateService` / `InstrumentService`（决定采集哪些 symbol）、
  `StrategyService`（只在篮子里选标的）、`MockExchangeGateway`（取折扣率算有效权益）。
- **更新方式**：`upsert`（按 base_coin 唯一）；`disableAll()` 用于重建篮子。
- **唯一键**：`base_coin`。
- **关键字段**：`base_coin`、`spot_symbol`、`futures_symbol`、`futures_category`、
  `discount_rate`、`real_supported`、`demo_supported`、`enabled`。

---

## 2. 资金费率表（策略的原材料）

### 2.1 `funding_rate_history` — 历史资金费率 ⭐

- **作用**：每个交易对在每个结算点的资金费率（结算周期因币而异），是一条时间序列。
  策略的"最近 10 天综合费率、正负累加"、恢复轮数过滤、mock 资金费结算，全基于它。
- **数据来源**：`/api/v3/market/history-fund-rate`（首次回补 90 天，之后增量）。
- **谁写**：`FundingRateService`。
- **谁读**：`FundingAnalysisService`（净年化、恢复轮数）、`MockExchangeGateway`（结算点）、后台费率曲线。
- **更新方式**：只追加；**从不删除**（接口只给 90 天，但本地保留更早的记录）。
- **唯一键**：`(symbol, funding_time)`（幂等去重）。
- **关键字段**：`symbol`、`funding_rate`、`funding_time`。

### 2.2 `funding_rate_current` — 实时资金费率快照

- **作用**：每个币"此刻"的费率 + **结算周期** + **下次结算时间** + 费率上下限。
  它是"每个币按什么节奏结算"的唯一依据。
- **数据来源**：`/api/v3/market/current-fund-rate`（每 10 分钟轮询）。
- **谁写**：`FundingRateService`。
- **谁读**：`FundingAnalysisService`（取结算周期折算年化）。
- **更新方式**：按 `symbol` 覆盖写，只保留最新一条。
- **唯一键**：`symbol`。
- **关键字段**：`funding_rate`、`funding_rate_interval`、`next_update_time`。

---

## 3. 行情与账户/持仓快照

### 3.1 `ticker_snapshot` — 行情快照

- **作用**：某个交易对在某一刻的价格与流动性状态。用于估值、算基差/价差、事后精确算滑点。
- **数据来源**：`/api/v3/market/tickers`（设计为"只在事件时写"，当前代码**尚未接入写入**，表已建好备用）。
- **谁读**：将来 `pnl`（估值）、`strategy`（基差）、后台。
- **更新方式**：追加。
- **关键字段**：`last_price`、`bid1_price`、`ask1_price`、`mark_price`、`index_price`、`turnover24h`、`source_time`。

### 3.2 `account_balance_snapshot` — 账户权益快照

- **作用**：账户整体权益（总权益、有效权益、维持保证金、保证金率、仓位价值）。**净值曲线就是它**。
- **数据来源**：实盘/官方模拟盘来自 `/api/v3/account/assets`（采集任务**尚未实现**）；
  **自建 mock 由 `SimulationService` 每小时写一条**（`source='mock'`）。
- **谁读**：`SimulationService`（算滚动年化、画净值曲线）。
- **更新方式**：追加。
- **关键字段**：`source`、`account_equity_usd`、`eff_equity`、`mmr`、`mgn_ratio`、`position_value`、`source_time`。

### 3.3 `account_asset_snapshot` — 币种资产快照

- **作用**：账户里每个币种的明细（余额/可用/锁定/折 USD）。开仓前算"能买多少现货"用。
- **数据来源**：`/api/v3/account/assets` 的 `assets` 数组（采集任务**尚未实现**）。
- **谁读**：将来 `strategy`、`risk`、后台。
- **更新方式**：追加，用 `balance_snapshot_id` 关联 3.2。
- **关键字段**：`balance_snapshot_id`、`coin`、`balance`、`available`、`usd_value`。

### 3.4 `position_snapshot` — 持仓快照

- **作用**：合约持仓在某一刻的状态（方向/数量/均价/未实现盈亏）。是"交易所视角"的原始持仓，
  与业务视角的 `strategy_position` 互补。
- **数据来源**：`/api/v3/position/current-position`（采集任务**尚未实现**）。
- **谁读**：将来 `reconciliation`（对账）、`pnl`、后台。
- **更新方式**：追加。
- **关键字段**：`source`、`symbol`、`pos_side`、`total`、`avg_price`、`unrealised_pnl`。

---

## 4. 事件表（账本）

### 4.1 `account_financial_record` — 账户资金流水 ⭐

- **作用**：账户里所有资金变动的流水。**这是资金费"真实到账"的唯一权威来源**（靠 `type` 字段筛）。
- **数据来源**：`/api/v3/account/financial-records`（实盘才写；采集任务**尚未实现**）。
- **谁读**：将来 `pnl`（资金费收入核算）。
- **更新方式**：只追加；唯一键 `record_id` 去重。
- **唯一键**：`record_id`。
- **关键字段**：`record_id`、`type`、`symbol`、`coin`、`amount`、`balance`、`source_time`。

### 4.2 `fee_rate` — 手续费率快照

- **作用**：手续费率（maker/taker）会随 VIP/活动变化，不能写死，要记录"当时的费率"。
- **数据来源**：实盘/官方模拟盘来自 `/api/v3/account/all-fee-rate`；**自建 mock 不写这张表**
  （mock 的费率是配置常量 `funding-yield.*-taker-fee-rate`，没有快照价值）。
- **谁写**：`FeeRateService`（每天一次，只写篮子里的币）。
- **谁读**：将来 `pnl`（成本计算）。
- **更新方式**：追加。
- **关键字段**：`symbol`、`category`、`maker_fee_rate`、`taker_fee_rate`、`source_time`。
- **注意**：这张表**没有 `source` 列**，所以不能让 mock 和 real 混写（mock 已跳过写入）。

---

## 5. 交易与策略表

### 5.1 `trade_order` — 订单

- **作用**：每一笔订单的本地记录 + 状态机。两个关键理由：① `client_oid` 唯一键做幂等
  （断网/重启后能判断"这笔单到底下没下"）；② 状态变化落库便于对账与排查。
- **数据来源**：实盘来自 `place-order` 返回 + `order-info` 回查（**实盘路径尚未接落库**）；
  **自建 mock 的 `MockExchangeGateway` 已完整落库**（`source='mock'`）。
- **谁读**：`MockExchangeGateway`（幂等查重、反查成交量）、后台订单列表。
- **更新方式**：插入 + 更新状态。
- **唯一键**：`client_oid`。
- **关键字段**：`source`、`client_oid`、`symbol`、`side`、`order_type`、`price`、`qty`、
  `cum_exec_qty`、`avg_price`、`order_status`、`reject_reason`。

### 5.2 `trade_fill` — 成交明细

- **作用**：每一笔成交（价、量、金额、手续费、滑点）。**模拟与真实共用一张表**，用 `source` 区分。
  这是"手续费磨损 / 滑点成本 / 已实现盈亏"的原始依据。
- **数据来源**：真实来自 `/api/v3/trade/fills`（**未接入**）；**mock 来自 `MockExchangeGateway` 撮合**。
- **谁读**：`SimulationService`（汇总手续费）、后台成交明细、将来 `pnl`。
- **更新方式**：只追加。
- **唯一键**：`dedup_key`（mock 用 `mock:<clientOid>`；real 用 `real:<execId>`）。
- **关键字段**：`source`、`dedup_key`、`leg`（spot/perp）、`trade_side`（open/close）、
  `exec_price`、`exec_qty`、`exec_value`、`fee`、`slippage`、`exec_pnl`（现货腿与永续腿的平仓已实现盈亏都已记录）。

### 5.3 `funding_income` — 资金费入账

- **作用**：每组持仓在每个结算点收/付的资金费，逐笔记录。策略的核心收益来源。
- **数据来源**：mock 由 `MockExchangeGateway` 按"真实费率 × 真实仓位"自算；real 用交易所流水核对（未做）。
- **谁读**：`SimulationService`（累计资金费）、后台资金费曲线。
- **更新方式**：只追加；**幂等靠唯一键，重启不重复入账**。
- **唯一键**：`(source, symbol, settlement_time)`。
- **关键字段**：`source`、`symbol`、`funding_rate`、`position_qty`、`mark_price`、
  `funding_amount`、`settlement_time`。

### 5.4 `strategy_position` — 策略仓位（对冲组合）

- **作用**：我们自己定义的"一组现货多 + 永续空"对冲仓位（业务视角）。交易所只知道两条腿，
  不知道它们是一对；这张表回答"这组开仓时间、两条腿数量/成本、是否还配对"。收益核算按"组"来。
- **数据来源**：策略开/平仓时写入（**模块 8 落地后才写，当前表已建好、暂无数据**）。
- **谁读**：将来 `pnl`、后台。
- **更新方式**：开仓插入，平仓更新 `status`。
- **关键字段**：`base_coin`、`spot_symbol`、`futures_symbol`、`status`、`open_time`、
  `close_time`、`spot_qty`、`futures_qty`、`spot_entry_price`、`futures_entry_price`。

---

## 6. 自建 mock 专用表

### 6.1 `mock_account` — 模拟账户现金（单行）

- **作用**：自建 mock 的"交易所账户"现金余额。只有 `simulation.enabled=true` 时有意义。
  **程序重启后从它恢复现金，接着上一次继续跑**，不会重置收益。
- **数据来源**：`MockExchangeGateway` 首次运行按 `simulation.initial-usdt` 初始化；之后每次买卖/收资金费时覆盖写余额。
- **谁读**：`MockExchangeGateway.loadState()`（启动恢复）。
- **更新方式**：单行 `upsert`。
- **主键**：`account_key`（固定 `mock`）。
- **关键字段**：`usdt_balance`、`initial_usdt`（初始资金，收益率分母，**首次初始化后不再覆盖**）。
- **重置方法**：想重新开始，删掉这一行（或把 `usdt_balance` 改回 `initial_usdt`）并删 `mock_position` / `trade_*` / `funding_income` 里的 `source='mock'` 行。

### 6.2 `mock_position` — 模拟持仓（每币一行）

- **作用**：自建 mock 的"交易所视角"持仓：现货腿数量 + 永续空头数量 + 开仓均价。
  **重启后从它恢复持仓**（与实盘"重启对账接管"同一条纪律）。
- **数据来源**：`MockExchangeGateway` 每次成交后 `upsert`；两条腿都归零时删除该行。
- **谁读**：`MockExchangeGateway.loadState()`（启动恢复）。
- **更新方式**：`upsert` / `delete`。
- **主键**：`base_coin`。
- **关键字段**：`spot_qty`、`spot_avg_price`（现货开仓均价，算现货已实现盈亏用）、
  `perp_qty`、`perp_avg_price`、`opened_at`（资金费只结算它之后的结算点）。

---

## 7. 数据流（谁写、谁读）

| 表 | 写 | 读 |
| --- | --- | --- |
| instrument | InstrumentService | OrderExecutionService / MockExchangeGateway |
| watch_coin | WatchCoinService | StrategyService / FundingRateService / InstrumentService / MockExchangeGateway |
| funding_rate_history | FundingRateService | FundingAnalysisService / MockExchangeGateway / 后台 |
| funding_rate_current | FundingRateService | FundingAnalysisService |
| ticker_snapshot | （未接入） | （未接入） |
| account_balance_snapshot | SimulationService（mock）/（real 未接入） | SimulationService |
| account_asset_snapshot | （未接入） | （未接入） |
| position_snapshot | （未接入） | （未接入） |
| account_financial_record | （未接入） | （未接入） |
| fee_rate | FeeRateService（非 mock） | （模块 8 用） |
| trade_order | MockExchangeGateway（real 未接入） | MockExchangeGateway / 后台 |
| trade_fill | MockExchangeGateway（real 未接入） | SimulationService / 后台 |
| funding_income | MockExchangeGateway | SimulationService / 后台 |
| mock_account | MockExchangeGateway | MockExchangeGateway |
| mock_position | MockExchangeGateway | MockExchangeGateway |
| strategy_position | （模块 8 用） | （模块 8 用） |
