# Bitget UTA 接口梳理（模块 1 依据）

> 账户类型：统一账户（UTA）。Base URL：`https://api.bitget.com`
> 来源：Bitget 官方 UTA 文档 + 官方 Java SDK（v3 接口定义，仅作参考，不引入依赖）
> 最后更新：2026-09-22

## 1. 通用规则

- 公共接口无需鉴权；私有接口需签名。
- 签名串：`timestamp + METHOD + requestPath + "?" + queryString + body`，HMAC-SHA256 后 Base64。请求头带 `ACCESS-KEY`、`ACCESS-SIGN`、`ACCESS-TIMESTAMP`、`ACCESS-PASSPHRASE`、`locale`、`Content-Type`。
- 限频：行情类多为 20 次/秒/IP；下单 10 次/秒/UID 等；超限返回 HTTP 429。
- 分页：游标分页（`cursor` 或 `idLessThan` + `limit`）；多数查询支持 `startTime` / `endTime`。
- `category` 取值：`SPOT` 现货、`MARGIN` 杠杆、`USDT-FUTURES` U 本位合约、`COIN-FUTURES` 币本位合约、`USDC-FUTURES` USDC 合约。

## 2. 需要的接口清单

### A. 行情与资金费率（公共）

| 用途 | 方法 | 路径 | 关键参数 | 关键返回 |
| --- | --- | --- | --- | --- |
| 服务器时间（签名校准） | GET | `/api/v3/market/time` | — | 服务器时间戳 |
| 交易对信息 | GET | `/api/v3/market/instruments` | `category`、`symbol` | symbol、baseCoin、quoteCoin、minOrderQty、maxOrderQty、pricePrecision、quantityPrecision、priceMultiplier、quantityMultiplier、makerFeeRate、takerFeeRate、type |
| 行情数据 | GET | `/api/v3/market/tickers` | `category`、`symbol` | lastPrice、bid1Price、ask1Price、bid1Size、ask1Size、markPrice、indexPrice、fundingRate、openInterest、turnover24h、volume24h、ts |
| 深度数据 | GET | `/api/v3/market/orderbook` | `category`、`symbol`、`limit` | a（卖档）、b（买档）、ts |
| **实时资金费率** | GET | `/api/v3/market/current-fund-rate` | `category`、`symbol`（二者不可同时为空） | symbol、fundingRate、fundingRateInterval（小时）、nextUpdate、minFundingRate、maxFundingRate |
| **历史资金费率** | GET | `/api/v3/market/history-fund-rate` | `category`（必填）、`symbol`（必填）、`cursor`、`limit` | resultList[ symbol、fundingRate、fundingRateTimestamp ]，仅最近 90 天 |
| 未平仓量 OI | GET | `/api/v3/market/open-interest` | `category`、`symbol` | symbol、openInterest、ts |
| K 线 | GET | `/api/v3/market/candles` / `/history-candles` | `category`、`symbol`、`interval` | K 线数组（回测备用） |
| 仓位档位 | GET | `/api/v3/market/position-tier` | `category`、`symbol` | 分档的杠杆与保证金要求（风控备用） |

### B. 账户（私有）

| 用途 | 方法 | 路径 | 关键参数 | 关键返回 |
| --- | --- | --- | --- | --- |
| **账户资产** | GET | `/api/v3/account/assets` | — | accountEquity、usdtEquity、unrealisedPnl、effEquity、mmr、imr、mgnRatio、positionValue、leverage、assets[ coin、equity、usdValue、balance、available、locked、debt ] |
| **交易手续费率** | GET | `/api/v3/account/fee-rate` | `symbol`、`category`（均必填）、`rpiFlag` | makerFeeRate、takerFeeRate |
| 全交易对手续费 | GET | `/api/v3/account/all-fee-rate` | `category` | 各 symbol 的费率 |
| **财务流水** | GET | `/api/v3/account/financial-records` | `category`（必填）、`coin`、`type`、`startTime`、`endTime`、`limit`、`cursor` | list[ id、symbol、coin、type、positionType、fee、positionAmount、positionBalance、amount、balance、ts ]、cursor |
| 账户信息 | GET | `/api/v3/account/info` | — | 账户基础信息 |
| 账户设置 | GET | `/api/v3/account/settings` | — | 持仓模式、杠杆等设置 |

### C. 仓位（私有）

| 用途 | 方法 | 路径 | 关键参数 | 关键返回 |
| --- | --- | --- | --- | --- |
| **当前持仓** | GET | `/api/v3/position/current-position` | `category`、`symbol`、`posSide` | category、symbol、marginCoin、posSide、positionBalance、available、frozen、total、leverage、curRealisedPnl、avgPrice、marginMode、holdMode、unrealisedPnl |
| 历史持仓 | GET | `/api/v3/position/history-position` | `category`、`symbol`、`startTime`、`endTime`、`cursor` | 已平仓位记录 |
| ADL 排名 | GET | `/api/v3/position/adlRank` | — | 自动减仓排名 |

### D. 交易（私有）

| 用途 | 方法 | 路径 | 关键参数 | 关键返回 |
| --- | --- | --- | --- | --- |
| **下单** | POST | `/api/v3/trade/place-order` | category、symbol、side、orderType、qty、price、timeInForce、posSide、clientOid、reduceOnly、stpMode | orderId、clientOid |
| **撤单** | POST | `/api/v3/trade/cancel-order` | orderId / clientOid、symbol、category | orderId、clientOid |
| 一键撤单 | POST | `/api/v3/trade/cancel-symbol-order` | symbol、category | — |
| **一键平仓** | POST | `/api/v3/trade/close-positions` | symbol、category、posSide | — |
| **订单详情** | GET | `/api/v3/trade/order-info` | orderId 或 clientOid | orderId、clientOid、symbol、category、price、qty、orderType、cumExecQty、cumExecValue、avgPrice、orderStatus、side、posSide、tradeSide、holdMode、marginMode、reduceOnly、feeDetail、createdTime、updatedTime |
| 当前委托 | GET | `/api/v3/trade/unfilled-orders` | category、symbol | 未成交订单列表 |
| 历史委托 | GET | `/api/v3/trade/history-orders` | category、symbol、startTime、endTime、cursor | 历史订单列表 |
| **成交明细** | GET | `/api/v3/trade/fills` | category、orderId、startTime、endTime、limit、cursor | execId、orderId、clientOid、symbol、category、orderType、execPrice、execQty、execValue、feeDetail、side、tradeScope、tradeSide、execPnl、createdTime |
| 批量下单 / 撤单 | POST | `/api/v3/trade/place-batch` / `/cancel-batch` | 订单数组 | 批量结果 |

## 3. 关键结论

- 资金费率历史只有最近 90 天，够支撑"10 天稳定"判定；要做更长回测，必须自己按结算点持续采集入库。
- 资金费率的"真实到账"以 `account/financial-records` 为准（按 `type` 过滤），`current-fund-rate` 只是预测/实时值。
- 下单必须带 `clientOid`（官方明确建议）：合约减仓单冲突时 `orderId` 会返回 null，只能靠 `clientOid` 兜底幂等。
- `order-info` 的 `delegateType` 枚举里存在 `strategy_arbitrage_positive` / `strategy_arbitrage_reverse`，说明 Bitget 自带"资金费套利策略"产品，后续可留意是否需要避让或借鉴。
- 下单量与精度受 `minOrderQty`、`quantityMultiplier`、`pricePrecision` 约束，现货还需参考平台交易规则页（接口不返回现货最小下单额）。
