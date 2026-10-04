package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单（对应表 {@code trade_order}）。
 *
 * <p>模拟与实盘共用这张表，用 {@link #source} 区分。它的两个作用：
 * <ol>
 *   <li><b>幂等</b>：{@link #clientOid} 是唯一键，断网 / 重启后能靠它判断"这笔单到底下没下"；</li>
 *   <li><b>状态机</b>：状态变化落库，便于对账与事后排查。</li>
 * </ol>
 */
public class TradeOrder {

    /** 主键。 */
    private Long id;

    /** 数据来源：mock 自建模拟 / demo 官方模拟盘 / real 实盘。 */
    private String source;

    /** 交易所订单号（mock 单是本地生成的假号）。 */
    private String exchangeOrderId;

    /** 自定义订单号（幂等键，唯一）。 */
    private String clientOid;

    /** 交易对。 */
    private String symbol;

    /** 产品线：SPOT / USDT-FUTURES。 */
    private String category;

    /** 买卖方向：buy / sell。 */
    private String side;

    /** 持仓方向：short 空 / long 多；现货单为空。 */
    private String posSide;

    /** 订单类型：limit 限价 / market 市价。 */
    private String orderType;

    /** 有效方式：fok / ioc / gtc；市价单为空。 */
    private String timeInForce;

    /** 委托价（市价单为空）。 */
    private BigDecimal price;

    /** 委托数量（基础币）。 */
    private BigDecimal qty;

    /** 累计成交数量（基础币）。 */
    private BigDecimal cumExecQty;

    /** 成交均价。 */
    private BigDecimal avgPrice;

    /** 状态：live / partially_filled / filled / cancelled / rejected。 */
    private String orderStatus;

    /** 是否只减仓：1 是 / 0 否。 */
    private Integer reduceOnly;

    /** 手续费合计（计价币）。 */
    private BigDecimal fee;

    /** 手续费币种。 */
    private String feeCoin;

    /** 被拒 / 被撤原因。 */
    private String rejectReason;

    /** 交易所创建时间。 */
    private LocalDateTime exchangeCreatedTime;

    /** 交易所更新时间。 */
    private LocalDateTime exchangeUpdatedTime;

    /** 本地入库时间。 */
    private LocalDateTime createdAt;

    /** 本地更新时间。 */
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getExchangeOrderId() { return exchangeOrderId; }
    public void setExchangeOrderId(String exchangeOrderId) { this.exchangeOrderId = exchangeOrderId; }

    public String getClientOid() { return clientOid; }
    public void setClientOid(String clientOid) { this.clientOid = clientOid; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getPosSide() { return posSide; }
    public void setPosSide(String posSide) { this.posSide = posSide; }

    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }

    public String getTimeInForce() { return timeInForce; }
    public void setTimeInForce(String timeInForce) { this.timeInForce = timeInForce; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }

    public BigDecimal getCumExecQty() { return cumExecQty; }
    public void setCumExecQty(BigDecimal cumExecQty) { this.cumExecQty = cumExecQty; }

    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }

    public String getOrderStatus() { return orderStatus; }
    public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }

    public Integer getReduceOnly() { return reduceOnly; }
    public void setReduceOnly(Integer reduceOnly) { this.reduceOnly = reduceOnly; }

    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }

    public String getFeeCoin() { return feeCoin; }
    public void setFeeCoin(String feeCoin) { this.feeCoin = feeCoin; }

    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    public LocalDateTime getExchangeCreatedTime() { return exchangeCreatedTime; }
    public void setExchangeCreatedTime(LocalDateTime exchangeCreatedTime) { this.exchangeCreatedTime = exchangeCreatedTime; }

    public LocalDateTime getExchangeUpdatedTime() { return exchangeUpdatedTime; }
    public void setExchangeUpdatedTime(LocalDateTime exchangeUpdatedTime) { this.exchangeUpdatedTime = exchangeUpdatedTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
