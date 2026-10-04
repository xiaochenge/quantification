package com.quantification.exchange;

/**
 * 交易（下单）运行模式——决定"下单请求最终落到哪里"。
 *
 * <p>三种模式的行情 / 费率 / 盘口<b>都读真实接口</b>，区别只在交易通道：
 * <ul>
 *   <li>{@link #REAL}：实盘，真钱真单（需要 {@code strategy.allow-real-trading=true} 这道安全闸）；</li>
 *   <li>{@link #DEMO}：Bitget 官方模拟盘（虚拟资金，真实撮合）；</li>
 *   <li>{@link #MOCK}：自建 mock，<b>下单只在本地模拟</b>，成交价按真实盘口深度推算。</li>
 * </ul>
 *
 * <p>为什么不直接用 {@code bitget.paptrading}：那个开关只能区分"实盘 / 官方模拟盘"，
 * 表达不了"自建 mock"这一档。所以保留它选实盘 / 官方模拟盘，新增
 * {@code simulation.enabled} 决定是否用自建 mock（见 {@link MockExchangeGateway}）。
 */
public enum TradeMode {

    /** 实盘：真钱真单。 */
    REAL("实盘"),

    /** Bitget 官方模拟盘：虚拟资金，走交易所真实撮合。 */
    DEMO("官方模拟盘"),

    /** 自建 mock：行情读真实接口，下单在本地模拟。 */
    MOCK("自建 mock");

    /** 人类可读的名称（日志与后台展示用）。 */
    private final String label;

    TradeMode(String label) {
        this.label = label;
    }

    /** @return 中文名称，如"自建 mock" */
    public String getLabel() {
        return label;
    }

    /** @return true 表示不会动用真实资金（官方模拟盘或自建 mock） */
    public boolean isSimulated() {
        return this != REAL;
    }
}
