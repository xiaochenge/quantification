package com.quantification.bitget;

import com.quantification.bitget.BitgetPublicClient.BitgetResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bitget 私有接口客户端（需要 API Key）：账户、持仓、下单、撤单、查单。
 *
 * <p><b>签名规则</b>（官方文档）：把 {@code timestamp + METHOD + requestPath + "?" + queryString + body}
 * 拼成一个字符串，用 SecretKey 做 HMAC-SHA256，再 Base64 编码，放进 ACCESS-SIGN 头。
 *
 * <p><b>为什么手工拼 JSON</b>：签名是对<b>实际发出的字节</b>计算的。若依赖序列化器生成 body，
 * 一旦序列化结果与签名时用的字符串有差异（字段顺序、空格），签名就会失败。
 * 所以这里统一用 {@link #toJson} 手工拼接，签名与发送用同一个字符串。
 *
 * <p><b>实盘 / 模拟盘</b>：由 {@code bitget.paptrading} 控制。true 用 demo-* 密钥并带
 * {@code paptrading: 1} 头；false 用实盘密钥。实测确认：实盘 key 加该头<b>不会</b>切到模拟盘。
 */
@Component
public class BitgetPrivateClient {

    private static final Logger log = LoggerFactory.getLogger(BitgetPrivateClient.class);

    private static final String OK = "00000";

    private final RestClient client;
    private final boolean paptrading;
    private final String realApiKey;
    private final String realSecretKey;
    private final String realPassphrase;
    private final String demoApiKey;
    private final String demoSecretKey;
    private final String demoPassphrase;

    public BitgetPrivateClient(@Value("${bitget.base-url:https://api.bitget.com}") String baseUrl,
                               @Value("${bitget.paptrading:false}") boolean paptrading,
                               @Value("${simulation.enabled:false}") boolean simulationEnabled,
                               @Value("${bitget.api-key:}") String realApiKey,
                               @Value("${bitget.secret-key:}") String realSecretKey,
                               @Value("${bitget.passphrase:}") String realPassphrase,
                               @Value("${bitget.demo-api-key:}") String demoApiKey,
                               @Value("${bitget.demo-secret-key:}") String demoSecretKey,
                               @Value("${bitget.demo-passphrase:}") String demoPassphrase) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.paptrading = paptrading;
        this.realApiKey = realApiKey;
        this.realSecretKey = realSecretKey;
        this.realPassphrase = realPassphrase;
        this.demoApiKey = demoApiKey;
        this.demoSecretKey = demoSecretKey;
        this.demoPassphrase = demoPassphrase;
        // 启动时把模式打到日志，避免"以为在模拟盘、实际在实盘"。
        // 自建 mock 模式下本类根本不会被调用（RealExchangeGateway 才用它），
        // 所以这里要明确说明，避免和"官方模拟盘"混淆。
        if (simulationEnabled) {
            log.info("自建 mock 模式：私有客户端不会被调用（不碰实盘、也不碰官方模拟盘账户）");
        } else if (paptrading) {
            log.info("Bitget 运行在【模拟盘】模式（paptrading=1，虚拟资金），不会动到真实资金");
        } else {
            log.info("Bitget 运行在【实盘】模式，下单会真实成交");
        }
    }

    /** @return 当前模式下是否配置齐全了密钥 */
    public boolean isConfigured() {
        return !apiKey().isBlank() && !secretKey().isBlank() && !passphrase().isBlank();
    }

    /** @return 是否处于模拟盘模式 */
    public boolean isPaperTrading() {
        return paptrading;
    }

    // ================= 账户与持仓 =================

    /**
     * 查询账户资产与权益。
     *
     * @return 账户权益（含 accountEquity 真实净值、effEquity 打折后可用于保证金的净值、mmr、mgnRatio）
     */
    public AccountAssets assets() {
        return get("/api/v3/account/assets", null, new ParameterizedTypeReference<BitgetResponse<AccountAssets>>() {})
                .data();
    }

    /**
     * 查询当前持仓。
     *
     * @param category 产品线
     * @param symbol   交易对（可空表示该产品线全部）
     * @return 仓位列表（无仓位时为空列表）
     */
    public List<Position> positions(String category, String symbol) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("category", category);
        if (symbol != null && !symbol.isBlank()) {
            query.put("symbol", symbol);
        }
        PositionData data = get("/api/v3/position/current-position", query,
                new ParameterizedTypeReference<BitgetResponse<PositionData>>() {}).data();
        return data == null || data.list() == null ? List.of() : data.list();
    }

    // ================= 交易 =================

    /**
     * 查询某个产品线下所有交易对的手续费率（账户真实值，含 VIP 折扣）。
     *
     * @param category 产品线，如 SPOT 或 USDT-FUTURES
     * @return 费率列表；未配置密钥时返回空列表
     */
    public List<FeeRateItem> allFeeRates(String category) {
        if (!isConfigured()) {
            log.warn("当前模式（{}）未配置 Bitget API Key，跳过手续费率查询", paptrading ? "模拟盘" : "实盘");
            return List.of();
        }
        BitgetResponse<List<FeeRateItem>> resp = get("/api/v3/account/all-fee-rate",
                Map.of("category", category),
                new ParameterizedTypeReference<BitgetResponse<List<FeeRateItem>>>() {});
        return resp.data() == null ? List.of() : resp.data();
    }

    /**
     * 下单。
     *
     * @param fields 请求字段（category / symbol / side / orderType / qty / price / posSide / clientOid 等）
     * @return 交易所返回的 orderId 与 clientOid（合约减仓单冲突时 orderId 可能为 null，只能靠 clientOid 兜底）
     */
    public OrderResult placeOrder(Map<String, Object> fields) {
        return post("/api/v3/trade/place-order", null, fields,
                new ParameterizedTypeReference<BitgetResponse<OrderResult>>() {}).data();
    }

    /**
     * 撤单。
     *
     * @param fields orderId 或 clientOid，配合 symbol / category
     * @return 被撤的订单标识
     */
    public OrderResult cancelOrder(Map<String, Object> fields) {
        return post("/api/v3/trade/cancel-order", null, fields,
                new ParameterizedTypeReference<BitgetResponse<OrderResult>>() {}).data();
    }

    /**
     * 查询订单详情。
     *
     * @param orderId   交易所订单号（与 clientOid 二选一）
     * @param clientOid 自定义订单号（与 orderId 二选一）
     * @return 订单详情；查不到时返回 null
     */
    public OrderInfo orderInfo(String orderId, String clientOid) {
        Map<String, String> query = new LinkedHashMap<>();
        if (orderId != null && !orderId.isBlank()) {
            query.put("orderId", orderId);
        } else if (clientOid != null && !clientOid.isBlank()) {
            query.put("clientOid", clientOid);
        } else {
            return null;
        }
        BitgetResponse<OrderInfo> resp = get("/api/v3/trade/order-info", query,
                new ParameterizedTypeReference<BitgetResponse<OrderInfo>>() {});
        return resp.data();
    }

    /**
     * 查询未成交订单。
     *
     * @param category 产品线
     * @return 未成交订单列表
     */
    public List<OrderInfo> unfilledOrders(String category) {
        OrderListData data = get("/api/v3/trade/unfilled-orders",
                Map.of("category", category),
                new ParameterizedTypeReference<BitgetResponse<OrderListData>>() {}).data();
        return data == null || data.unfilledList() == null ? List.of() : data.unfilledList();
    }

    // ================= 内部：签名与请求 =================

    private <T> BitgetResponse<T> get(String path, Map<String, String> query,
                                      ParameterizedTypeReference<BitgetResponse<T>> type) {
        return send("GET", path, buildQuery(query), null, type);
    }

    private <T> BitgetResponse<T> post(String path, Map<String, String> query, Map<String, Object> fields,
                                       ParameterizedTypeReference<BitgetResponse<T>> type) {
        return send("POST", path, buildQuery(query), toJson(fields), type);
    }

    private <T> BitgetResponse<T> send(String method, String path, String query, String body,
                                       ParameterizedTypeReference<BitgetResponse<T>> type) {
        if (!isConfigured()) {
            throw new BitgetApiException("当前模式（" + (paptrading ? "模拟盘" : "实盘") + "）未配置 Bitget API Key");
        }
        RestClient.RequestHeadersSpec<?> spec = "GET".equals(method)
                ? client.get().uri(uri -> {
                    var b = uri.path(path);
                    if (query != null && !query.isEmpty()) {
                        for (String pair : query.split("&")) {
                            String[] kv = pair.split("=", 2);
                            b = b.queryParam(kv[0], kv.length > 1 ? kv[1] : "");
                        }
                    }
                    return b.build();
                })
                : client.post().uri(uri -> {
                    var b = uri.path(path);
                    if (query != null && !query.isEmpty()) {
                        for (String pair : query.split("&")) {
                            String[] kv = pair.split("=", 2);
                            b = b.queryParam(kv[0], kv.length > 1 ? kv[1] : "");
                        }
                    }
                    return b.build();
                }).contentType(MediaType.APPLICATION_JSON).body(body);

        if (paptrading) {
            spec = spec.header("paptrading", "1");
        }
        // lambda 只能捕获"实际上的最终变量"，这里先固化
        RestClient.RequestHeadersSpec<?> finalSpec = spec;
        // 网络抖动自动退避重试；业务错误不重试。
        // 注意：时间戳与签名必须在【每次尝试内部】重新生成——
        // 曾经把它们算在重试外面，结果重试带着过期签名，被交易所拒为
        // "40008 请求时间戳过期"。
        BitgetResponse<T> resp = HttpRetry.call(() -> {
            String timestamp = String.valueOf(System.currentTimeMillis());
            return finalSpec
                    .header("ACCESS-KEY", apiKey())
                    .header("ACCESS-SIGN", sign(timestamp, method, path, query, body))
                    .header("ACCESS-TIMESTAMP", timestamp)
                    .header("ACCESS-PASSPHRASE", passphrase())
                    .header("locale", "zh-CN")
                    .retrieve()
                    .body(type);
        }, method + " " + path);
        if (resp == null || !OK.equals(resp.code())) {
            throw new BitgetApiException(path + " 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp;
    }

    private static String buildQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    /**
     * 手工拼 JSON。
     *
     * <p>用 LinkedHashMap 保证字段顺序稳定，且返回的字符串同时用于"签名"和"发送"，
     * 避免序列化差异导致签名不一致。
     *
     * @param fields 字段（值为 null 输出 null，数字/布尔原样输出，其他按字符串转义）
     * @return 单层 JSON 字符串
     */
    private static String toJson(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object value = e.getValue();
            if (value == null) {
                sb.append("null");
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else {
                sb.append('"').append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private String sign(String timestamp, String method, String requestPath, String queryString, String body) {
        String preHash = timestamp + method + requestPath
                + (queryString == null || queryString.isEmpty() ? "" : "?" + queryString)
                + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(preHash.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BitgetApiException("生成签名失败: " + e.getMessage());
        }
    }

    private String apiKey() {
        return paptrading ? demoApiKey : realApiKey;
    }

    private String secretKey() {
        return paptrading ? demoSecretKey : realSecretKey;
    }

    private String passphrase() {
        return paptrading ? demoPassphrase : realPassphrase;
    }

    // ================= 响应结构 =================

    /**
     * 账户资产。
     *
     * @param accountEquity 账户总权益（真实净值，USD）
     * @param effEquity     有效权益（打折后可用于保证金的净值，USD）
     * @param mmr           维持保证金（USD）
     * @param mgnRatio      维持保证金率（实测 = mmr ÷ effEquity）
     * @param positionValue 仓位价值（USD）
     * @param assets        各币种资产
     */
    public record AccountAssets(String accountEquity, String effEquity, String mmr, String mgnRatio,
                                String positionValue, List<AssetCoin> assets) {
    }

    /**
     * 单个币种资产。
     *
     * @param coin      币种
     * @param available 可用数量
     * @param equity    权益（以币种计）
     * @param usdValue  折 USD 价值
     */
    public record AssetCoin(String coin, String available, String equity, String usdValue) {
    }

    /**
     * 持仓。
     *
     * @param symbol        交易对
     * @param posSide       持仓方向（long / short）
     * @param total         仓位总数量
     * @param available     可用数量
     * @param avgPrice      平均开仓价
     * @param unrealisedPnl 未实现盈亏
     * @param leverage      杠杆
     * @param marginMode    保证金模式（crossed / isolated）
     */
    public record Position(String symbol, String posSide, String total, String available, String avgPrice,
                           String unrealisedPnl, String leverage, String marginMode) {
    }

    /** 持仓查询的 data 外壳。 */
    public record PositionData(List<Position> list) {
    }

    /**
     * 下单 / 撤单的返回。
     *
     * @param orderId   交易所订单号（可能为 null，见类注释）
     * @param clientOid 自定义订单号
     */
    public record OrderResult(String orderId, String clientOid) {
    }

    /**
     * 订单信息。
     *
     * @param orderId    交易所订单号
     * @param clientOid  自定义订单号
     * @param symbol     交易对
     * @param orderStatus 状态：live / new / partially_filled / filled / cancelled
     * @param price      委托价
     * @param qty        委托数量
     * @param cumExecQty 累计成交数量
     * @param avgPrice   成交均价
     * @param side       买卖方向
     * @param posSide    持仓方向
     * @param createdTime 创建时间（毫秒）
     */
    public record OrderInfo(String orderId, String clientOid, String symbol, String orderStatus,
                            String price, String qty, String cumExecQty, String avgPrice,
                            String side, String posSide, String createdTime) {
    }

    /** 未成交订单列表的 data 外壳。 */
    public record OrderListData(List<OrderInfo> unfilledList, String cursor) {
    }

    /**
     * 手续费率的一条记录。
     *
     * @param symbol       交易对
     * @param makerFeeRate 挂单费率（小数）
     * @param takerFeeRate 吃单费率（小数）
     */
    public record FeeRateItem(String symbol, String makerFeeRate, String takerFeeRate) {
    }
}
