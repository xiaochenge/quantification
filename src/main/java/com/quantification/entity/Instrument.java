package com.quantification.entity;

import java.math.BigDecimal;

/**
 * 交易对规则，对应表 {@code instrument}。
 *
 * <p><b>为什么必须落库</b>：下单的数量 / 价格必须满足交易所的精度、最小最大值与乘数，
 * 这些值<b>只能从交易所接口查询，绝不能猜</b>，也不能下单时临时去查（慢且可能拿到脏数据）。
 * 正确做法：定时（每小时）同步进本表，下单时<b>从库里取</b>。
 *
 * <p>反面教材（2026-09-23 实测）：因为没按规则校验数量小数位，现货腿下单被拒
 * （`40808 size checkBDScale error`），而合约腿已经成交，留下裸多头敞口，
 * 正好撞上行情下跌，造成实际亏损。
 */
public class Instrument {

    /** 主键。 */
    private Long id;

    /** 产品线：SPOT / USDT-FUTURES 等。 */
    private String category;

    /** 交易对。 */
    private String symbol;

    /** 基础币。 */
    private String baseCoin;

    /** 计价币。 */
    private String quoteCoin;

    /** 合约类型（如 perpetual）；现货为空。 */
    private String contractType;

    /** 最小下单数量。 */
    private BigDecimal minOrderQty;

    /** 单笔最大下单数量（0 表示不限）。 */
    private BigDecimal maxOrderQty;

    /** 价格精度（小数位数）。 */
    private Integer pricePrecision;

    /** 数量精度（小数位数）。 */
    private Integer quantityPrecision;

    /** 市价下单的计价精度。 */
    private Integer quotePrecision;

    /** 价格乘数（合约）。 */
    private BigDecimal priceMultiplier;

    /** 数量乘数（合约）。 */
    private BigDecimal quantityMultiplier;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getBaseCoin() { return baseCoin; }
    public void setBaseCoin(String baseCoin) { this.baseCoin = baseCoin; }

    public String getQuoteCoin() { return quoteCoin; }
    public void setQuoteCoin(String quoteCoin) { this.quoteCoin = quoteCoin; }

    public String getContractType() { return contractType; }
    public void setContractType(String contractType) { this.contractType = contractType; }

    public BigDecimal getMinOrderQty() { return minOrderQty; }
    public void setMinOrderQty(BigDecimal minOrderQty) { this.minOrderQty = minOrderQty; }

    public BigDecimal getMaxOrderQty() { return maxOrderQty; }
    public void setMaxOrderQty(BigDecimal maxOrderQty) { this.maxOrderQty = maxOrderQty; }

    public Integer getPricePrecision() { return pricePrecision; }
    public void setPricePrecision(Integer pricePrecision) { this.pricePrecision = pricePrecision; }

    public Integer getQuantityPrecision() { return quantityPrecision; }
    public void setQuantityPrecision(Integer quantityPrecision) { this.quantityPrecision = quantityPrecision; }

    public Integer getQuotePrecision() { return quotePrecision; }
    public void setQuotePrecision(Integer quotePrecision) { this.quotePrecision = quotePrecision; }

    public BigDecimal getPriceMultiplier() { return priceMultiplier; }
    public void setPriceMultiplier(BigDecimal priceMultiplier) { this.priceMultiplier = priceMultiplier; }

    public BigDecimal getQuantityMultiplier() { return quantityMultiplier; }
    public void setQuantityMultiplier(BigDecimal quantityMultiplier) { this.quantityMultiplier = quantityMultiplier; }
}
