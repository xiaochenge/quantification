package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 历史资金费率的一条记录，对应表 {@code funding_rate_history}。
 *
 * <p>每个交易对在每个结算点产生一条，是"稳定高费"判定与模拟 / 回测的核心数据。
 * 唯一键 {@code (symbol, funding_time)} 保证重复采集不会产生重复行。
 */
public class FundingRateHistory {

    /** 主键。 */
    private Long id;

    /** 交易对，如 BTCUSDT。 */
    private String symbol;

    /** 产品线，如 USDT-FUTURES。 */
    private String category;

    /** 本次结算的资金费率，小数形式（0.0001 表示 0.01%）。正数表示多方付给空方。 */
    private BigDecimal fundingRate;

    /** 结算时间（Asia/Shanghai 的可读时间）。 */
    private LocalDateTime fundingTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getFundingRate() { return fundingRate; }
    public void setFundingRate(BigDecimal fundingRate) { this.fundingRate = fundingRate; }

    public LocalDateTime getFundingTime() { return fundingTime; }
    public void setFundingTime(LocalDateTime fundingTime) { this.fundingTime = fundingTime; }
}
