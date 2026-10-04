package com.quantification.exchange;

import com.quantification.bitget.BitgetPrivateClient;
import com.quantification.bitget.BitgetPrivateClient.AccountAssets;
import com.quantification.bitget.BitgetPrivateClient.FeeRateItem;
import com.quantification.bitget.BitgetPrivateClient.OrderInfo;
import com.quantification.bitget.BitgetPrivateClient.OrderResult;
import com.quantification.bitget.BitgetPrivateClient.Position;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 真实交易所网关：把 {@link ExchangeGateway} 的调用原样转发给 {@code BitgetPrivateClient}。
 *
 * <p>它覆盖两种模式——实盘（{@code bitget.paptrading=false}）与 Bitget 官方模拟盘
 * （{@code bitget.paptrading=true}）。两者的区别（用哪套密钥、带不带 {@code paptrading:1} 头）
 * 已经在 {@code BitgetPrivateClient} 内部处理，这里不做二次判断。
 *
 * <p>装配条件：{@code simulation.enabled} 不为 true 时启用（默认就是它）。
 */
@Component
@ConditionalOnProperty(name = "simulation.enabled", havingValue = "false", matchIfMissing = true)
public class RealExchangeGateway implements ExchangeGateway {

    /** 低层 Bitget 私有接口客户端（签名、模拟盘头、网络重试都在它里面）。 */
    private final BitgetPrivateClient client;

    /**
     * @param client Bitget 私有接口客户端
     */
    public RealExchangeGateway(BitgetPrivateClient client) {
        this.client = client;
    }

    /** @return 官方模拟盘模式返回 {@link TradeMode#DEMO}，否则 {@link TradeMode#REAL} */
    @Override
    public TradeMode mode() {
        return client.isPaperTrading() ? TradeMode.DEMO : TradeMode.REAL;
    }

    @Override
    public boolean isConfigured() {
        return client.isConfigured();
    }

    @Override
    public AccountAssets assets() {
        return client.assets();
    }

    @Override
    public List<Position> positions(String category, String symbol) {
        return client.positions(category, symbol);
    }

    @Override
    public List<FeeRateItem> allFeeRates(String category) {
        return client.allFeeRates(category);
    }

    @Override
    public OrderResult placeOrder(Map<String, Object> fields) {
        return client.placeOrder(fields);
    }

    @Override
    public OrderResult cancelOrder(Map<String, Object> fields) {
        return client.cancelOrder(fields);
    }

    @Override
    public OrderInfo orderInfo(String orderId, String clientOid) {
        return client.orderInfo(orderId, clientOid);
    }

    @Override
    public List<OrderInfo> unfilledOrders(String category) {
        return client.unfilledOrders(category);
    }
}
