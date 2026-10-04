# 数据模型设计（模块 2 · 草案）

> 状态：一期数据层已落地并通过验证（详见第 11 节）
> 最后更新：2026-09-22

## 0. 本轮已确认的决策

1. **账户权益 / 持仓快照：每 1 小时同步一次**（取最小粒度）。
2. **`ticker_snapshot` 只在事件时写入**（下单 / 平仓前后各一份），不做定时轮询。
3. **K 线（candles）不落库**，一期用不到。
4. **资金种费率只采集 90 天（接口上限）**；后台展示"该币最近年化收益"，计算时**正负费率都要累加**（净额口径，见 4.1）。
5. **后台年化默认展示"含手续费损耗"的净值口径**，另提供"纯资金费"口径作对照。
6. **模拟成交与真实成交合并成一张表**，用 `source` 区分（原 `sim_trade` 取消）。
7. 因第 6 条，模拟与真实共用同一套交易表，一期就把统一表建好；原"一期 / 二期分批建表"的问题作废（见第 9 节）。

## 1. 设计约定

- 引擎 InnoDB，字符集 utf8mb4，排序 utf8mb4_0900_ai_ci。
- 主键 BIGINT UNSIGNED AUTO_INCREMENT。
- 时间统一用 `DATETIME` 存可读时间（Asia/Shanghai），**不用毫秒时间戳**；Java 侧用 `LocalDateTime`，只在调用交易所的边界处做毫秒转换。（详见 `docs/coding-standards.md`）
- 金额/数量用 `DECIMAL(38,18)`（交易所返回字符串，精度高，避免浮点误差）。
- 状态/枚举用 `VARCHAR(32)`。
- 幂等靠唯一键：事件型数据（资金费率、成交、流水、订单）必须能去重。
- **资金费结算周期因币种而异**：Bitget 各币的结算周期可能是 1 / 2 / 4 / 8 小时，不是所有币都 8 小时。年化计算与采集调度都要按每个币的实际周期来。

## 2. 表分三类，先理解这个分类

- **元数据表**：描述"世界长什么样"（有哪些交易对、费率精度、我们监控哪些币）。变化慢，可以覆盖写。
- **快照表**：描述"某一刻的状态"（账户有多少钱、仓位多大、价格多少）。随时间追加，用来画曲线。
- **事件表**：描述"发生了什么"（下单、成交、资金费到账）。只追加、不覆盖，是最底层的账本。
- **策略表**：描述"我们自己的决策结果"（一组对冲仓位、模拟成交、每日汇总）。是业务视角，不是交易所视角。

数据流向：`Bitget 接口 → 采集 → 快照表/事件表 → 分析/策略/核算 → 看板`。

下面每张表按统一格式说明：作用、为什么需要、数据来源、谁在用、更新方式、数据量。

## 3. 元数据表

### 3.1 instrument — 交易对元数据

**作用**：Bitget 上每个交易对的静态规格字典（现货、合约各一行）。

**为什么需要**：下单量必须满足 `minOrderQty`、是 `quantityMultiplier` 的整数倍、价格要符合 `pricePrecision`，否则直接被拒单。算目标仓位、拆单、模拟成交也都要这些精度和乘数。它不是"行情"，而是"规则"。

**数据来源**：`/api/v3/market/instruments`，按 `category` 分别拉（`SPOT` 和 `USDT-FUTURES` 各一次）。

**谁在用**：exchange-gateway（下单前校验）、strategy（算目标仓位）、simulation（模拟成交）、admin（展示）。

**更新方式**：按 `(symbol, category)` 覆盖写；启动时 + 每天刷新一次即可。

**数据量**：很小，几百行。

**关键字段**：category、symbol、base_coin、quote_coin、min_order_qty、max_order_qty、price_precision、quantity_precision、quote_precision、price_multiplier、quantity_multiplier、maker_fee_rate、taker_fee_rate、type。

### 3.2 watch_coin — 监控篮子

**作用**：我们"主动选出来要监控"的币种名单，每个币种对应现货和永续两个交易对。

**为什么需要**：Bitget 上币种成百上千，但我们只做一批主流币。把"篮子"独立成表，就能随时加币、减币（改数据，不改代码），也让资金费率采集知道该拉哪些 symbol。`enabled` 字段可以做开关，比如某币临时不做就关掉。

**数据来源**：一期人工维护（就是你之前认可的那批主流币）；以后可以按市值 + 流动性自动筛选。

**谁在用**：funding-analysis（决定采集哪些 symbol）、strategy（只在篮子里选标的）、admin（展示监控范围）。

**更新方式**：手工增删改。

**数据量**：极小，十几到几十行。

**关键字段**：base_coin（唯一）、spot_symbol、futures_symbol、enabled、added_at、note。

## 4. 资金费率（策略的原材料）

### 4.1 funding_rate_history — 历史资金费率 ⭐

**作用**：每个交易对在每个结算点的资金费率（结算周期因币而异，见第 1 节），是一条时间序列。

**为什么需要**：这是整个策略最核心的数据。你的核心规则"最近 10 天费率稳定在高位"完全基于它计算；模拟交易时"每期收多少资金费"也来自它；以后回测也全靠它。没有这张表，策略无从谈起。

**数据来源**：首次用 `/api/v3/market/history-fund-rate` 回补最近 90 天；之后按结算点持续追加（可用 `current-fund-rate` 轮询，或定时拉 history）。

**谁在用**：funding-analysis（算综合年化、稳定性、筛选高费币）、simulation（模拟资金费到账）、admin（画费率曲线）。

**更新方式**：只追加；唯一键 `(symbol, funding_time)` 保证重复拉取不会重复入库。

**数据量**：币种数 × 3 次/天 × 天数。10 个币种一年约 1.1 万行，很小，可以长期保留。

**关键字段**：symbol、category、funding_rate（小数，如 0.0001 = 0.01%）、funding_time（结算时间，可读时间）、created_at。

### 4.2 funding_rate_current — 实时资金费率快照

**作用**：每个交易对"此刻"的费率和结算信息。

**为什么需要**：history 记录的是过去，current 回答的是"下一次什么时候结算、现在的费率是多少、费率上下限是多少"。程序需要一个地方知道"什么时候该采集/结算"，看板也需要实时显示当前费率。它和 history 的区别是：history 是序列，current 是"最新状态"。

**数据来源**：`/api/v3/market/current-fund-rate`，定时轮询（比如每分钟）。

**谁在用**：funding-analysis（实时展示、触发结算任务）、schedule（决定结算点任务）、admin。

**更新方式**：按 symbol 覆盖写，只保留最新一条。

**数据量**：等于币种数，几十行。

**关键字段**：symbol、funding_rate、funding_rate_interval（结算周期小时）、next_update_time（下次结算时间）、min_funding_rate、max_funding_rate、source_time。

**为什么保留（重要）**：它不是历史序列，但提供两样历史表给不了的东西——① 每个币**实际的下次结算时间**（`nextUpdate`），调度要用；② 每个币**实际的结算周期**（`fundingRateInterval`）。Bitget 各币结算周期不统一（1 / 2 / 4 / 8 小时），这张表是"每个币按什么节奏结算"的唯一依据。

## 5. 行情快照

### 5.1 ticker_snapshot — 行情快照

**作用**：每个交易对在某一刻的价格与流动性状态（最新价、买卖一、标记价、指数价、成交量、持仓量）。

**为什么需要**：三件事都靠它——① 估值：算现货腿和合约腿的市值；② 成本：现货和永续之间的价差（基差）、买卖一价差，直接决定换仓成本；③ 筛选：24 小时成交额决定这个币流动性够不够。没有它，策略没法算"这次调仓划不划算"。

**数据来源**：`/api/v3/market/tickers`（算滑点时可再加 `/market/orderbook`）。

**谁在用**：pnl（估值）、strategy（算基差和成本）、simulation（模拟成交价）、admin（行情展示）。

**更新方式**：**只在事件时写入**（已确认）——每次下单 / 平仓前后各存一份（含买一卖一价），用于事后精确计算滑点和真实成本。不做定时轮询。

**数据量**：等于交易事件次数 × 每事件快照份数，量很小，长期保留无压力。

**说明**：不做定时快照后就没有连续的基差曲线；以后若需要，再加一个低频定时采集即可，不影响表结构。

**关键字段**：symbol、category、last_price、bid1_price、ask1_price、bid1_size、ask1_size、mark_price、index_price、funding_rate、open_interest、turnover24h、volume24h、source_time。

## 6. 账户与持仓快照

### 6.1 account_balance_snapshot — 账户权益快照

**作用**：整个统一账户在某一刻的整体权益（总权益、未实现盈亏、保证金占用、维持保证金率、杠杆）。

**为什么需要**：看板上那条"净资产曲线"就来自这张表；同时它是风控的输入——维持保证金率接近危险值时要预警。它记录的是账户级别的"总账状态"。

**数据来源**：`/api/v3/account/assets`。

**谁在用**：pnl（净资产曲线）、risk（保证金与强平预警）、admin。

**更新方式**：追加快照；**每 1 小时一次**（已确认）。

**数据量**：一年约 8760 行，很小。

**关键字段**：account_equity_usd、usdt_equity、unrealised_pnl_usd、eff_equity、mmr（维持保证金）、imr（初始保证金）、mgn_ratio（维持保证金率）、position_value、leverage、source_time。

### 6.2 account_asset_snapshot — 币种资产快照

**作用**：账户里**每个币种**的明细（余额、可用、锁定、负债、折 USD 价值）。

**为什么需要**：统一账户里现货和合约共用保证金，所以必须知道"每个币种实际有多少可用"。开仓前要算"能买多少现货、能开多大空仓"；现货腿建完仓后也要核对币是不是到位了。总权益（上一张表）回答"一共有多少钱"，这张表回答"钱分别是什么、能不能用"。

**数据来源**：`/api/v3/account/assets` 的 `assets` 数组。

**谁在用**：strategy（算可用资金）、risk、admin。

**更新方式**：追加快照；**每 1 小时一次**（与上一张表同一次采集）；用 `balance_snapshot_id` 关联。

**数据量**：账户权益快照数 × 币种数。

**关键字段**：balance_snapshot_id、coin、equity、usd_value、balance、available、locked、debt、source_time。

### 6.3 position_snapshot — 持仓快照

**作用**：合约持仓在某一刻的状态（方向、数量、保证金、开仓均价、未实现盈亏、杠杆、持仓模式）。

**为什么需要**：这是"交易所眼里的真实持仓"。策略判断、风控、收益核算都要看它；更重要的是**重启接管**——程序重启后必须拉真实持仓来恢复状态，绝不能凭本地记录就重新开仓。它和策略自己的仓位表（`strategy_position`）的区别是：这张是交易所视角的原始仓位，策略表是业务视角的对冲组合。

**数据来源**：`/api/v3/position/current-position`。

**谁在用**：strategy、risk、reconciliation（对账/重启恢复）、pnl、admin。

**更新方式**：追加快照；**每 1 小时一次**（已确认）。

**数据量**：一年约 8760 行 × 有仓位的币种数，很小。

**关键字段**：symbol、category、margin_coin、pos_side、position_balance、available、frozen、total、leverage、avg_price、unrealised_pnl、cur_realised_pnl、margin_mode、hold_mode、source_time。

## 7. 交易事件（账本）

### 7.1 trade_order — 订单

**作用**：我们下的每一笔订单的本地记录，带状态机。

**为什么需要**：两个关键理由。① 幂等：官方明确说，合约减仓单冲突时下单返回的 `orderId` 会是 null，只能靠我们传的 `clientOid` 兜底，所以本地表必须以 `clientOid` 为唯一键，重试才不会重复下单。② 状态追踪：订单从 `live → partially_filled → filled/cancelled`，要能在本地追踪，用来对账和排查。

**数据来源**：`place-order` 的返回 + `order-info` / `unfilled-orders` 回查补全。

**谁在用**：execution（下单与状态机）、reconciliation（对账）、pnl（手续费）、admin（订单列表）。

**更新方式**：插入 + 更新状态。

**数据量**：订单数，中等。

**关键字段**：source（mock/real）、exchange_order_id、client_oid（唯一）、symbol、category、side、pos_side、order_type、time_in_force、price、qty、cum_exec_qty、avg_price、order_status、reduce_only、fee、fee_coin、exchange_created_time、exchange_updated_time、local_created_at、local_updated_at。

**说明**：与 `trade_fill` 一致，模拟与真实共用这张表，用 `source` 区分。

### 7.2 trade_fill — 成交明细（模拟 + 真实合并）

**作用**：每一笔成交记录（成交价、数量、金额、手续费、方向、盈亏）。**模拟成交和真实成交共用这一张表**，用 `source` 字段区分。

**为什么需要**：你最关心的"手续费磨损"就在这张表——每笔手续费记录下来才能算真实成本；它也是精确对账和已实现盈亏的依据。订单表是"我下了什么"，这张表是"到底成交了什么"。让模拟成交也写进同一张表（`source=mock`），模拟和实盘的评估口径就完全一致，代码也只用一套。

**数据来源**：真实来自 `/api/v3/trade/fills`；模拟来自 simulation 模块（模拟手续费与滑点）。

**谁在用**：pnl（成本与盈亏）、simulation、reconciliation、admin（成交明细）。

**更新方式**：只追加。去重键 `dedup_key`（唯一）：真实成交用 `real:<execId>`，模拟成交用 `mock:<策略仓位ID>:<序号>`。

**数据量**：成交笔数，中等。

**关键字段**：source（mock/real）、dedup_key（唯一）、exec_id（真实才有）、order_id、client_oid、strategy_position_id（模拟才有）、leg（spot/perp）、symbol、category、side、trade_side（开/平）、order_type、exec_price、exec_qty、exec_value、trade_scope（maker/taker，决定费率）、fee、fee_coin、fee_detail_json、slippage（模拟才有）、exec_pnl、created_time。

### 7.3 account_financial_record — 账户财务流水 ⭐

**作用**：账户里所有资金变动的流水（资金费、手续费、划转、借贷利息等）。

**为什么需要**：这是资金费"真实到账"的**唯一权威来源**。`current-fund-rate` 只是实时/预测值，真正加钱扣钱发生在流水里，靠 `type` 字段区分（资金费对应的具体枚举值在文档 `/docs/uta/enum` 的 financial record 部分，落库前我去核对）。核算收益、验证模拟是否贴近真实，都必须用它。

**数据来源**：`/api/v3/account/financial-records`。

**谁在用**：pnl（资金费收入核算）、admin（流水明细）。

**更新方式**：只追加；唯一键 `record_id` 去重。

**数据量**：资金来源/去向条数，中等。

**关键字段**：record_id（唯一）、category、symbol、coin、type、position_type、fee、position_amount、position_balance、amount、balance、source_time。

### 7.4 fee_rate — 手续费率快照

**作用**：交易手续费率（maker / taker）的快照。

**为什么需要**：成本模型的核心输入。手续费率不是固定的——会随 VIP 等级、活动变化。所以不能写死，要记录"当时的费率"。用 `/api/v3/account/all-fee-rate` 可以一次拿到全部交易对的费率，比逐个查 `fee-rate` 高效。

**数据来源**：`/api/v3/account/fee-rate` 或 `/api/v3/account/all-fee-rate`。

**谁在用**：pnl（成本计算）、strategy（算"费率差 > 换仓成本"的阈值）、simulation（模拟费用）。

**更新方式**：追加快照（费率变化时）。

**数据量**：小。

**关键字段**：symbol、category、maker_fee_rate、taker_fee_rate、rpi_flag、source_time。

## 8. 策略与模拟

### 8.1 strategy_position — 策略仓位（对冲组合）

**作用**：我们自己定义的一组"现货多 + 永续空"的对冲仓位。

**为什么需要**：交易所只告诉你"有 0.5 个 BTC 现货"和"0.5 个 BTC 合约空仓"，但它不知道这两条腿是一组配对的对冲。策略需要知道"这一组的开仓时间、两条腿的数量和成本、当前是否还配对"。这是业务视角的仓位，和交易所视角的 `position_snapshot` 互补。收益核算也按"组"来算。

**数据来源**：策略开仓 / 平仓时写入。

**谁在用**：strategy、pnl（按组算收益）、admin（展示每组持仓）。

**更新方式**：开仓插入，平仓更新状态。

**数据量**：小到中等（取决于调仓频率）。

**关键字段**：base_coin、spot_symbol、futures_symbol、status（open/closed）、open_time、close_time、spot_qty、futures_qty、spot_entry_price、futures_entry_price、note。

### 8.2 funding_income — 资金费收入

**作用**：每条策略仓位在每个结算点收到的资金费，逐笔记录。

**为什么需要**：资金费是策略的核心收益来源。这张表回答"这组仓位靠资金费赚了多少"。它用 `source` 区分 mock 和 real：模拟阶段按历史费率和新仓位数模拟出应收金额；实盘阶段则从 `account_financial_record` 里匹配真实到账，两者可以对比，验证模拟是否靠谱。

**数据来源**：mock 阶段由 simulation 按 `funding_rate_history` 计算；实盘阶段来自流水匹配。

**谁在用**：pnl、admin（资金费收益曲线）。

**更新方式**：只追加。

**关键字段**：strategy_position_id、symbol、funding_rate、funding_amount、settlement_time、source（mock/real）。

### 8.3 pnl_daily — 每日盈亏汇总

**作用**：按天汇总的收益（资金费收入、手续费成本、已实现/未实现盈亏、净资产）。

**为什么需要**：看板的主图（收益曲线）和"收益是否稳定"的判断依据；也是每天对账的基线。前面那些表是明细，这张是给人和看板看的汇总。

**数据来源**：由其他表汇总计算。

**谁在用**：admin、你做决策。

**更新方式**：每日定时生成，可重算（重算不丢原始数据）。

**关键字段**：date、funding_income、fee_cost、realized_pnl、unrealized_pnl、net_equity、note。

## 9. 一期建表清单（分批建表的问题已作废）

因为第 5 条决定"模拟与真实共用同一套交易表"，所以一期就把这套统一表建好，模拟阶段先写 `source=mock` 的数据。原"一期不建交易表、二期再建"的建议作废。

唯一例外是 `account_financial_record`（交易所账户流水）：它来自交易所真实账户，模拟阶段没有对应来源，可以现在建好、先留空，等实盘再写入。

**一期建表清单（共 15 张）**：

- 元数据：`instrument`、`watch_coin`
- 费率：`funding_rate_history`、`funding_rate_current`
- 行情：`ticker_snapshot`
- 账户持仓快照：`account_balance_snapshot`、`account_asset_snapshot`、`position_snapshot`
- 交易（模拟 + 真实共用）：`trade_order`、`trade_fill`、`fee_rate`
- 策略与核算：`strategy_position`、`funding_income`、`pnl_daily`
- 交易所流水（实盘才写入，先建空表）：`account_financial_record`

## 10. 剩余待确认问题

1. `ticker_snapshot` 的"事件时"具体记哪几个时点？（建议：下单前、下单后、平仓前、平仓后各一份）
2. 资金费历史只有 90 天（接口上限），是否需要从旧文档 / 第三方补齐更长历史用于回测？

## 11. 实现状态（2026-09-22）

- **建表**：V1 迁移建好一期 10 张表；V2 灌入 14 个监控币种。均已在 `quantification_test` 库验证。
- **时间字段**：全部用 `DATETIME` 存可读时间（如 `2026-09-22 16:00:00`），Java 侧用 `LocalDateTime`，时区 `Asia/Shanghai`。
- **采集**：历史资金费率 14 币 × 90 天 = 3780 行；实时资金费率 14 行。**全部走公共接口，不需要 API Key。**
- **调度**：实时费率每 10 分钟、历史回补每 6 小时各自动跑一次；另有手动触发接口
  `POST /api/admin/collect/funding-rate/history` 与 `POST /api/admin/collect/funding-rate/current`。
- **密钥位置（后续私有接口用）**：`src/main/resources/application-local.yml`（已进 .gitignore，不会提交）。

**首采基线（近 90 天，正负累加；换仓成本按 45 天轮换一次、双边吃单估算）**：

| 币种 | 毛年化（纯资金费） | 换仓成本年化 | 净年化 |
| --- | --- | --- | --- |
| DOT | 8.39% | 2.60% | 5.79% |
| BNB | 7.90% | 2.60% | 5.31% |
| DOGE | 7.85% | 2.60% | 5.26% |
| LTC | 7.12% | 2.60% | 4.53% |
| SUI | 6.99% | 2.60% | 4.39% |
| AVAX | 6.74% | 2.60% | 4.14% |
| LINK | 6.62% | 2.60% | 4.03% |
| XRP | 6.12% | 2.60% | 3.52% |
| SHIB | 5.51% | 2.60% | 2.92% |
| ETH | 5.36% | 2.60% | 2.76% |
| BTC | 5.26% | 2.60% | 2.66% |
| SOL | 4.70% | 2.60% | 2.11% |
| ADA | 4.20% | 2.60% | 1.61% |
| TRX | −7.27% | 2.60% | −9.87% |

三个结论供参考：① 扣掉换仓成本后，最好的是 DOT 5.79%，**整篮平均只有约 2.8%**，明显低于 10% 目标——说明"在这批币里轮动"不够，必须靠更精准的择时或更宽的高费币池；② TRX 毛年化为负，证明"正负累加"不是理论问题，篮子里的币不能一视同仁；③ 换仓成本每年吃掉 2.60%，如果轮换更频繁（比如 15 天一次），成本会升到约 7.8%，直接吃光收益——这也是"10 天稳定才动手"这条规则的价值所在。

计算结果可通过 `GET /api/admin/funding-yield` 复现（参数在 application.yml 的 `funding-yield` 段）。

### 全市场扫描（2026-09-22，篮子扩到"现货 + U 本位永续都有"的全部币种）

同步结果：现货 515 个、永续 466 个，取交集后 **351 个币**；采集到 154,393 行历史费率。

**重要发现：结算周期不是 8 小时**。351 个币里 266 个是 **4 小时**、84 个是 8 小时、1 个是 1 小时。
之前那 14 个币恰好全是 8 小时，所以没暴露出来。这意味着：① 采集频率要按 4 小时为主；② 年化计算必须用每个币自己的周期。

**含手续费净年化（45 天轮换、双边吃单、成本年化 2.60%），按流动性分档**：

| 范围 | 币种数 | 净为正 | 净≥5% | 净≥10% | 平均净年化 |
| --- | --- | --- | --- | --- | --- |
| 全市场 | 351 | 256 | 182 | 40 | −4.92% |
| 24h 成交额 ≥ 500 万 | 57 | 51 | 26 | 5 | 5.24% |
| 24h 成交额 ≥ 2000 万 | 27 | 25 | 8 | **0** | 3.87% |

**结论**：净年化 ≥10% 的币确实存在（40 个），但**全部落在成交额不足 500 万美元的尾部**——这些币的 24h 成交额大多不到 100 万美元，真实滑点会远超手续费，"2.60% 成本年化"这个假设对它们不成立。真正能交易的（成交额 ≥2000 万）里，**没有一个达到 10%**，最好的是 MUBARAK 7.87%、WIF 7.66%、TAO 6.37%、ENA 5.93%、ONDO 5.90%。

所以 10% 目标能不能达到，取决于两点：一是愿不愿意承担小币的流动性风险（还要把滑点算进去），二是择时能不能把收益率再抬一截。下一步建议：给候选币加上"成交额 + 盘口价差"的准入过滤，把滑点纳入成本模型。

**据此确认的门槛（2026-09-23）**：目标币种池 = 现货与永续都有 **且 24h 成交额 ≥ 500 万美元**（配置项 `watch-coin.min-turnover`）；收益目标为**理想总体年化 8%**。换仓成本改用私有接口返回的**账户真实费率**（现货 0.06% / 合约 0.0375%），年化成本约 1.58%。用真实费率回测：≥500 万池年化 11%~22%，≥2000 万池只有 3.7%。理由见 `docs/requirements.md` 第 4 节与 `docs/phase-2-strategy-design.md` 第 4 节。

## 12. 交易 / 策略 / 模拟表已落地（2026-09-24，迁移 V5）

模块 9（自建 mock）之前，交易与策略相关的表一直**没有真正建出来**（V1 只建了 10 张数据层表），
模拟成交无处落库、只能靠"反查交易所状态"做幂等。V5 补齐了 6 张：

| 表 | 用途 | 谁在写 |
| --- | --- | --- |
| `trade_order` | 订单账本 + 状态机（唯一键 `client_oid`） | mock 撮合写 `source=mock`；实盘路径待接 |
| `trade_fill` | 成交明细（唯一键 `dedup_key`：`mock:<clientOid>`） | 同上；含滑点与手续费原始值 |
| `funding_income` | 资金费逐笔入账（唯一键 `source + symbol + settlement_time`） | 自建 mock 自己算（真实费率 × 真实仓位） |
| `mock_account` | 自建 mock 的"交易所账户"现金（单行表） | `exchange/MockExchangeGateway` |
| `mock_position` | 自建 mock 的持仓（现货 + 永续两条腿） | 同上；重启后由它恢复仓位 |
| `strategy_position` | 策略视角的对冲组合（业务视角） | **暂未写入**，留给模块 8（盈亏核算） |

`pnl_daily`（每日汇总）仍未建，等模块 8 落地时再补。
与实盘共用同一套表的做法见 `docs/phase-2-strategy-design.md` 第 10.5 节。
