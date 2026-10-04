package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 成交明细（对应表 {@code trade_fill}）：成本与盈亏的原始依据。
 *
 * <p>模拟与实盘共用这张表，用 {@link #source} 区分。模拟成交的
 * {@link #slippage} 在这里留痕——它是"这笔单实际比盘口最优价差了多少"，
 * 也是模块 8 成本归因的输入。
 */
public class TradeFill {

    /** 主键。 */
    private Long id;

    /** 数据来源：mock / demo / real。 */
    private String source;

    /** 去重键：真实成交 real:&lt;execId&gt;，模拟成交 mock:&lt;clientOid&gt;。 */
    private String dedupKey;

    /** 交易所成交 ID（模拟成交为空）。 */
    private String execId;

    /** 关联 trade_order.id。 */
    private Long orderId;

    /** 下单时的自定义订单号。 */
    private String clientOid;

    /** 关联 strategy_position.id（模块 8 落地后回填）。 */
    private Long strategyPositionId;

    /** 腿：spot 现货 / perp 永续。 */
    private String leg;

    /** 交易对。 */
    private String symbol;

    /** 产品线。 */
    private String category;

    /** 买卖方向：buy / sell。 */
    private String side;

    /** 开平方向：open 开仓 / close 平仓。 */
    private String tradeSide;

    /** 订单类型：limit / market。 */
    private String orderType;

    /** 成交均价。 */
    private BigDecimal execPrice;

    /** 成交数量（基础币）。 */
    private BigDecimal execQty;

    /** 成交金额（计价币）。 */
    private BigDecimal execValue;

    /** 吃单 / 挂单：taker / maker。 */
    private String tradeScope;

    /** 手续费（计价币，正数表示支出）。 */
    private BigDecimal fee;

    /** 手续费币种。 */
    private String feeCoin;

    /** 滑点：成交均价相对下单前盘口最优价的偏移（小数）。 */
    private BigDecimal slippage;

    /** 本笔已实现盈亏（平仓腿才有）。 */
    private BigDecimal execPnl;

    /** 成交时间。 */
    private LocalDateTime createdTime;

    /** 本地入库时间。 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }

    public String getExecId() { return execId; }
    public void setExecId(String execId) { this.execId = execId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getClientOid() { return clientOid; }
    public void setClientOid(String clientOid) { this.clientOid = clientOid; }

    public Long getStrategyPositionId() { return strategyPositionId; }
    public void setStrategyPositionId(Long strategyPositionId) { this.strategyPositionId = strategyPositionId; }

    public String getLeg() { return leg; }
    public void setLeg(String leg) { this.leg = leg; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getTradeSide() { return tradeSide; }
    public void setTradeSide(String tradeSide) { this.tradeSide = tradeSide; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public BigDecimal getExecPrice() { return execPrice; }
    public void setExecPrice(BigDecimal execPrice) { this.execPrice = execPrice; }

    public BigDecimal getExecQty() { return execQty; }
    public void setExecQty(BigDecimal execQty) { this.execQty = execQty; }

    public BigDecimal getExecValue() { return execValue; }
    public void setExecValue(BigDecimal execValue) { this.execValue = execValue; }

    public String getTradeScope() { return tradeScope; }
    public void setTradeScope(String tradeScope) { this.tradeScope = tradeScope; }

    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }

    public String getFeeCoin() { return feeCoin; }
    public void setFeeCoin(String feeCoin) { this.feeCoin = feeCoin; }

    public BigDecimal getSlippage() { return slippage; }
    public void setSlippage(BigDecimal slippage) { this.slippage = slippage; }

    public BigDecimal getExecPnl() { return execPnl; }
    public void setExecPnl(BigDecimal execPnl) { this.execPnl = execPnl; }

    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
