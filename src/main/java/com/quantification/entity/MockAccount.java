package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 自建 mock 的"交易所账户"（对应表 {@code mock_account}，单行）。
 *
 * <p>它只在 {@code simulation.enabled=true} 时有意义：自建 mock 里没有真实的交易所账户，
 * 所有现金收支（买现货、卖现货、手续费、资金费）都记在这里。
 * <b>落库的意义</b>：程序重启后能接着上一次的余额继续跑，而不是从零开始。
 */
public class MockAccount {

    /** 账户标识，固定为 mock（保证只有一行）。 */
    private String accountKey;

    /** USDT 现金余额（含手续费与资金费的收支）。 */
    private BigDecimal usdtBalance;

    /** 模拟账户初始 USDT 资金（用于算累计收益率）。 */
    private BigDecimal initialUsdt;

    /** 本地入库时间。 */
    private LocalDateTime createdAt;

    /** 本地更新时间。 */
    private LocalDateTime updatedAt;

    public String getAccountKey() { return accountKey; }
    public void setAccountKey(String accountKey) { this.accountKey = accountKey; }

    public BigDecimal getUsdtBalance() { return usdtBalance; }
    public void setUsdtBalance(BigDecimal usdtBalance) { this.usdtBalance = usdtBalance; }

    public BigDecimal getInitialUsdt() { return initialUsdt; }
    public void setInitialUsdt(BigDecimal initialUsdt) { this.initialUsdt = initialUsdt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
