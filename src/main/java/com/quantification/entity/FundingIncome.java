package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金费入账（对应表 {@code funding_income}）：策略的核心收益来源。
 *
 * <p>自建 mock 里由我们自己算：<b>真实费率 × 真实仓位</b>（官方模拟盘的资金费金额实测不可信，
 * 差 2.7 倍，所以必须自己记账）。唯一键是
 * {@code (source, symbol, settlement_time)}，同一个结算点只会入账一次，重启也不会重复加钱。
 */
public class FundingIncome {

    /** 主键。 */
    private Long id;

    /** 数据来源：mock 自算 / real 交易所流水核对。 */
    private String source;

    /** 关联 strategy_position.id（模块 8 落地后回填）。 */
    private Long strategyPositionId;

    /** 永续交易对。 */
    private String symbol;

    /** 产品线。 */
    private String category;

    /** 本次结算的资金费率（小数）。 */
    private BigDecimal fundingRate;

    /** 结算时的持仓数量（基础币，空头为正数）。 */
    private BigDecimal positionQty;

    /** 结算时用的最新价（名义价值 = 数量 × 该价格）。 */
    private BigDecimal markPrice;

    /** 入账金额（USDT，正数=收到，负数=付出）。 */
    private BigDecimal fundingAmount;

    /** 结算币种，通常为 USDT。 */
    private String coin;

    /** 结算时间。 */
    private LocalDateTime settlementTime;

    /** 本地入库时间。 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getStrategyPositionId() { return strategyPositionId; }
    public void setStrategyPositionId(Long strategyPositionId) { this.strategyPositionId = strategyPositionId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getFundingRate() { return fundingRate; }
    public void setFundingRate(BigDecimal fundingRate) { this.fundingRate = fundingRate; }

    public BigDecimal getPositionQty() { return positionQty; }
    public void setPositionQty(BigDecimal positionQty) { this.positionQty = positionQty; }

    public BigDecimal getMarkPrice() { return markPrice; }
    public void setMarkPrice(BigDecimal markPrice) { this.markPrice = markPrice; }

    public BigDecimal getFundingAmount() { return fundingAmount; }
    public void setFundingAmount(BigDecimal fundingAmount) { this.fundingAmount = fundingAmount; }

    public String getCoin() { return coin; }
    public void setCoin(String coin) { this.coin = coin; }

    public LocalDateTime getSettlementTime() { return settlementTime; }
    public void setSettlementTime(LocalDateTime settlementTime) { this.settlementTime = settlementTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
