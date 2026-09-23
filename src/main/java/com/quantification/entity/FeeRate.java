package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 手续费率快照，对应表 {@code fee_rate}。
 *
 * <p>费率会随 VIP 等级和活动变化，所以按快照方式追加记录，而不是覆盖写。
 */
public class FeeRate {

    /** 主键。 */
    private Long id;

    /** 交易对。 */
    private String symbol;

    /** 产品线，如 SPOT / USDT-FUTURES。 */
    private String category;

    /** 挂单费率（小数，0.0006 表示 0.06%）。 */
    private BigDecimal makerFeeRate;

    /** 吃单费率（小数）。 */
    private BigDecimal takerFeeRate;

    /** 是否 RPI 费率：yes / no。 */
    private String rpiFlag;

    /** 采集时间。 */
    private LocalDateTime sourceTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public BigDecimal getMakerFeeRate() { return makerFeeRate; }
    public void setMakerFeeRate(BigDecimal makerFeeRate) { this.makerFeeRate = makerFeeRate; }

    public BigDecimal getTakerFeeRate() { return takerFeeRate; }
    public void setTakerFeeRate(BigDecimal takerFeeRate) { this.takerFeeRate = takerFeeRate; }

    public String getRpiFlag() { return rpiFlag; }
    public void setRpiFlag(String rpiFlag) { this.rpiFlag = rpiFlag; }

    public LocalDateTime getSourceTime() { return sourceTime; }
    public void setSourceTime(LocalDateTime sourceTime) { this.sourceTime = sourceTime; }
}
