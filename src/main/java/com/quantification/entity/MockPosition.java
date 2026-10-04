package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 自建 mock 的持仓（对应表 {@code mock_position}，每个币一行）。
 *
 * <p>这就是"模拟交易所眼里的持仓"：现货腿数量 + 永续空头数量 + 开仓均价。
 * 策略循环读的 {@code positions()} / {@code assets()} 都由它换算出来，
 * 所以它落库以后，程序重启也能接管上一次的模拟仓位（与实盘的"重启对账"同一条纪律）。
 */
public class MockPosition {

    /** 标的币种，如 BTC。 */
    private String baseCoin;

    /** 现货交易对。 */
    private String spotSymbol;

    /** 永续交易对。 */
    private String futuresSymbol;

    /** 现货多头数量（基础币）。 */
    private BigDecimal spotQty;

    /** 现货多头开仓均价（平仓时用它算已实现盈亏）。 */
    private BigDecimal spotAvgPrice;

    /** 永续空头数量（基础币，正数表示空头）。 */
    private BigDecimal perpQty;

    /** 永续空头开仓均价。 */
    private BigDecimal perpAvgPrice;

    /** 本币这组仓位的开仓时间（资金费只结算该时间之后的结算点）。 */
    private LocalDateTime openedAt;

    /** 本地更新时间。 */
    private LocalDateTime updatedAt;

    public String getBaseCoin() { return baseCoin; }
    public void setBaseCoin(String baseCoin) { this.baseCoin = baseCoin; }

    public String getSpotSymbol() { return spotSymbol; }
    public void setSpotSymbol(String spotSymbol) { this.spotSymbol = spotSymbol; }

    public String getFuturesSymbol() { return futuresSymbol; }
    public void setFuturesSymbol(String futuresSymbol) { this.futuresSymbol = futuresSymbol; }

    public BigDecimal getSpotQty() { return spotQty; }
    public void setSpotQty(BigDecimal spotQty) { this.spotQty = spotQty; }

    public BigDecimal getSpotAvgPrice() { return spotAvgPrice; }
    public void setSpotAvgPrice(BigDecimal spotAvgPrice) { this.spotAvgPrice = spotAvgPrice; }

    public BigDecimal getPerpQty() { return perpQty; }
    public void setPerpQty(BigDecimal perpQty) { this.perpQty = perpQty; }

    public BigDecimal getPerpAvgPrice() { return perpAvgPrice; }
    public void setPerpAvgPrice(BigDecimal perpAvgPrice) { this.perpAvgPrice = perpAvgPrice; }

    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime openedAt) { this.openedAt = openedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
