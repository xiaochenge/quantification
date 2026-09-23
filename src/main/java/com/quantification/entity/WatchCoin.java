package com.quantification.entity;

/**
 * 监控篮子中的一个币种，对应表 {@code watch_coin}。
 *
 * <p>篮子决定"资金费率采集要拉哪些交易对"、"策略可以在哪些标的上开仓"。
 * 一个币种对应现货和永续两个交易对，两者必须都存在才可入选。
 */
public class WatchCoin {

    /** 主键。 */
    private Long id;

    /** 标的币种，如 BTC。 */
    private String baseCoin;

    /** 对应的现货交易对，如 BTCUSDT。 */
    private String spotSymbol;

    /** 对应的永续交易对，如 BTCUSDT。 */
    private String futuresSymbol;

    /** 永续所属产品线，如 USDT-FUTURES。 */
    private String futuresCategory;

    /** 是否启用：1 启用 / 0 停用。停用后采集和策略都会跳过。 */
    private Integer enabled;

    /** 备注。 */
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getBaseCoin() { return baseCoin; }
    public void setBaseCoin(String baseCoin) { this.baseCoin = baseCoin; }

    public String getSpotSymbol() { return spotSymbol; }
    public void setSpotSymbol(String spotSymbol) { this.spotSymbol = spotSymbol; }

    public String getFuturesSymbol() { return futuresSymbol; }
    public void setFuturesSymbol(String futuresSymbol) { this.futuresSymbol = futuresSymbol; }

    public String getFuturesCategory() { return futuresCategory; }
    public void setFuturesCategory(String futuresCategory) { this.futuresCategory = futuresCategory; }

    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
