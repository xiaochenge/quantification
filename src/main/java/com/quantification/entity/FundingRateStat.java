package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资金费率按币种聚合后的统计结果（<b>查询投影，不是数据库表</b>）。
 *
 * <p>由 {@code FundingRateHistoryMapper.summarizeSince} 直接填充，供收益计算使用。
 */
public class FundingRateStat {

    /** 交易对。 */
    private String symbol;

    /** 窗口内的结算次数。 */
    private Integer sampleCount;

    /** 窗口内所有结算费率之和（正负都累加）。 */
    private BigDecimal rateSum;

    /** 窗口内最早的结算时间。 */
    private LocalDateTime firstTime;

    /** 窗口内最晚的结算时间。 */
    private LocalDateTime lastTime;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public Integer getSampleCount() { return sampleCount; }
    public void setSampleCount(Integer sampleCount) { this.sampleCount = sampleCount; }

    public BigDecimal getRateSum() { return rateSum; }
    public void setRateSum(BigDecimal rateSum) { this.rateSum = rateSum; }

    public LocalDateTime getFirstTime() { return firstTime; }
    public void setFirstTime(LocalDateTime firstTime) { this.firstTime = firstTime; }

    public LocalDateTime getLastTime() { return lastTime; }
    public void setLastTime(LocalDateTime lastTime) { this.lastTime = lastTime; }
}
