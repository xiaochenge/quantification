package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户权益快照（对应表 {@code account_balance_snapshot}）。
 *
 * <p>实盘 / 官方模拟盘由采集任务写入（source=demo 或 real）；<b>自建 mock 由模拟交易所自己写</b>
 * （source=mock）——它就是模拟盘的"净值曲线"数据源，滚动年化也从这张表算。
 */
public class AccountBalanceSnapshot {

    /** 主键。 */
    private Long id;

    /** 数据来源：mock 自建模拟 / demo 官方模拟盘 / real 实盘。 */
    private String source;

    /** 账户总权益（USD），含现货市值与合约未实现盈亏。 */
    private BigDecimal accountEquityUsd;

    /** 账户总权益（USDT），模拟账户这里等于 USDT 现金。 */
    private BigDecimal usdtEquity;

    /** 账户总未实现盈亏（USD）。 */
    private BigDecimal unrealisedPnlUsd;

    /** 有效权益（打折后可用于保证金的净值，USD）。 */
    private BigDecimal effEquity;

    /** 维持保证金（USD）。 */
    private BigDecimal mmr;

    /** 维持保证金率（= mmr ÷ effEquity）。 */
    private BigDecimal mgnRatio;

    /** 仓位名义价值（USD）。 */
    private BigDecimal positionValue;

    /** 快照时间。 */
    private LocalDateTime sourceTime;

    /** 本地入库时间。 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public BigDecimal getAccountEquityUsd() { return accountEquityUsd; }
    public void setAccountEquityUsd(BigDecimal accountEquityUsd) { this.accountEquityUsd = accountEquityUsd; }

    public BigDecimal getUsdtEquity() { return usdtEquity; }
    public void setUsdtEquity(BigDecimal usdtEquity) { this.usdtEquity = usdtEquity; }

    public BigDecimal getUnrealisedPnlUsd() { return unrealisedPnlUsd; }
    public void setUnrealisedPnlUsd(BigDecimal unrealisedPnlUsd) { this.unrealisedPnlUsd = unrealisedPnlUsd; }

    public BigDecimal getEffEquity() { return effEquity; }
    public void setEffEquity(BigDecimal effEquity) { this.effEquity = effEquity; }

    public BigDecimal getMmr() { return mmr; }
    public void setMmr(BigDecimal mmr) { this.mmr = mmr; }

    public BigDecimal getMgnRatio() { return mgnRatio; }
    public void setMgnRatio(BigDecimal mgnRatio) { this.mgnRatio = mgnRatio; }

    public BigDecimal getPositionValue() { return positionValue; }
    public void setPositionValue(BigDecimal positionValue) { this.positionValue = positionValue; }

    public LocalDateTime getSourceTime() { return sourceTime; }
    public void setSourceTime(LocalDateTime sourceTime) { this.sourceTime = sourceTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
