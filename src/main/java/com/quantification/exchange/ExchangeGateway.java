package com.quantification.exchange;

import com.quantification.bitget.BitgetPrivateClient.AccountAssets;
import com.quantification.bitget.BitgetPrivateClient.FeeRateItem;
import com.quantification.bitget.BitgetPrivateClient.OrderInfo;
import com.quantification.bitget.BitgetPrivateClient.OrderResult;
import com.quantification.bitget.BitgetPrivateClient.Position;
import java.util.List;
import java.util.Map;

/**
 * 交易所网关（模块 1 定的隔离层，模块 9 补齐）。
 *
 * <p><b>为什么要有这一层</b>：策略、执行、风控、对账都只认这个接口，不关心背后是实盘、
 * 官方模拟盘还是自建 mock。这样"用同一套代码验证策略"才成立——
 * 换模式只换实现，业务代码一行都不用改。
 *
 * <p><b>两个实现</b>：
 * <ul>
 *   <li>{@link RealExchangeGateway}：转发给 {@code BitgetPrivateClient}（实盘 / 官方模拟盘）；</li>
 *   <li>{@link MockExchangeGateway}：自建模拟撮合（只读真实行情，绝不发出真实交易请求）。</li>
 * </ul>
 * 由配置 {@code simulation.enabled} 二选一装配。
 *
 * <p><b>注意</b>：行情类接口（盘口、最新价、资金费率、交易对规则）不走这里，
 * 它们本来就不需要密钥，直接由 {@code BitgetPublicClient} 提供。
 */
public interface ExchangeGateway {

    /** @return 当前交易模式（实盘 / 官方模拟盘 / 自建 mock） */
    TradeMode mode();

    /** @return 当前模式是否具备下单能力（自建 mock 恒为 true，它不需要密钥） */
    boolean isConfigured();

    /**
     * 查询账户资产与权益。
     *
     * @return 账户权益（总权益 / 有效权益 / 维持保证金 / 保证金率 / 各币资产）
     */
    AccountAssets assets();

    /**
     * 查询当前持仓。
     *
     * @param category 产品线（如 USDT-FUTURES）
     * @param symbol   交易对（空表示该产品线全部）
     * @return 持仓列表，无持仓为空列表
     */
    List<Position> positions(String category, String symbol);

    /**
     * 查询某个产品线下所有交易对的账户真实手续费率。
     *
     * @param category 产品线（SPOT / USDT-FUTURES）
     * @return 费率列表；自建 mock 返回配置里的假设费率
     */
    List<FeeRateItem> allFeeRates(String category);

    /**
     * 下单。
     *
     * @param fields 请求字段（category / symbol / side / orderType / qty / price / posSide / clientOid）
     * @return 交易所订单号与自定义订单号
     * @throws com.quantification.bitget.BitgetApiException 下单被拒（余额不足、精度不符、深度不足等）
     */
    OrderResult placeOrder(Map<String, Object> fields);

    /**
     * 撤单。
     *
     * @param fields orderId 或 clientOid，配合 symbol / category
     * @return 被撤订单的标识
     * @throws com.quantification.bitget.BitgetApiException 订单不存在或已是终态
     */
    OrderResult cancelOrder(Map<String, Object> fields);

    /**
     * 反查订单详情（下单后必须以它拿到的成交量为准，不能把"接口返回成功"当成交）。
     *
     * @param orderId   交易所订单号（与 clientOid 二选一）
     * @param clientOid 自定义订单号（与 orderId 二选一）
     * @return 订单详情；查不到返回 null
     */
    OrderInfo orderInfo(String orderId, String clientOid);

    /**
     * 查询未成交订单。
     *
     * @param category 产品线
     * @return 未成交订单列表
     */
    List<OrderInfo> unfilledOrders(String category);

    /**
     * 结算资金费。
     *
     * <p>实盘与官方模拟盘由交易所自己结算，这里什么都不做；<b>自建 mock 必须自己算</b>——
     * 它的"交易所"是我们写的，没人替它入账（官方模拟盘的资金费金额实测也不可信，差 2.7 倍）。
     */
    default void settleFunding() {
        // 默认空实现：真实交易所会自己结算
    }
}
