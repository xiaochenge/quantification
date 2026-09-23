package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 实时资金费率快照，对应表 {@code funding_rate_current}。
 *
 * <p>与历史表的区别：历史表是时间序列，本表每个币只保留最新一条（覆盖写），
 * 提供"当前费率、结算周期、下次结算时间"这些调度和展示需要的信息。
 */
public class FundingRateCurrent {

    /** 主键。 */
    private Long id;

    /** 交易对，如 BTCUSDT。 */
    private String symbol;

    /** 产品线，如 USDT-FUTURES。 */
    private String category;

    /** 当前资金费率，小数形式。 */
    private BigDecimal fundingRate;

    /** 结算周期，单位小时（1 / 2 / 4 / 8），各币可能不同。 */
    private Integer fundingRateInterval;

    /** 下次结算时间（Asia/Shanghai 的可读时间）。 */
    private LocalDateTime nextUpdateTime;

    /** 资金费率下限。 */
    private BigDecimal minFundingRate;

    /** 资金费率上限。 */
    private BigDecimal maxFundingRate;

    /** 本条数据的采集时间。 */
    private LocalDateTime sourceTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getFundingRate() { return fundingRate; }
    public void setFundingRate(BigDecimal fundingRate) { this.fundingRate = fundingRate; }

    public Integer getFundingRateInterval() { return fundingRateInterval; }
    public void setFundingRateInterval(Integer fundingRateInterval) { this.fundingRateInterval = fundingRateInterval; }

    public LocalDateTime getNextUpdateTime() { return nextUpdateTime; }
    public void setNextUpdateTime(LocalDateTime nextUpdateTime) { this.nextUpdateTime = nextUpdateTime; }

    public BigDecimal getMinFundingRate() { return minFundingRate; }
    public void setMinFundingRate(BigDecimal minFundingRate) { this.minFundingRate = minFundingRate; }

    public BigDecimal getMaxFundingRate() { return maxFundingRate; }
    public void setMaxFundingRate(BigDecimal maxFundingRate) { this.maxFundingRate = maxFundingRate; }

    public LocalDateTime getSourceTime() { return sourceTime; }
    public void setSourceTime(LocalDateTime sourceTime) { this.sourceTime = sourceTime; }
}
