package com.quantification.bitget;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bitget 公共行情接口客户端（无需 API Key）。
 *
 * <p><b>为什么不用官方 Java SDK</b>：官方 SDK 没有发布到任何 Maven 仓库（Maven Central 与
 * Sonatype 快照仓库均为 404），且是 Java 8 + 老依赖（lombok 1.16.20、fastjson 1.2.70 等），
 * 在当前 JDK 24 上无法编译。所以这里用 Spring 自带的 {@link RestClient} 直接调 REST 接口，
 * 后续私有接口的签名逻辑按官方文档自行实现。
 *
 * <p>本类只做两件事：调接口、把响应解析成对象。不含业务逻辑，业务在 service 层。
 * 调用失败统一抛 {@link BitgetApiException}。
 */
@Component
public class BitgetPublicClient {

    /** 产品线标识：U 本位永续合约。 */
    public static final String USDT_FUTURES = "USDT-FUTURES";

    /** 产品线标识：现货。 */
    public static final String SPOT = "SPOT";

    /** Bitget 成功响应的业务码。 */
    private static final String OK = "00000";

    /** HTTP 客户端，baseUrl 取自配置 bitget.base-url。 */
    private final RestClient client;

    /**
     * @param baseUrl Bitget 接口地址，默认 https://api.bitget.com
     */
    public BitgetPublicClient(@Value("${bitget.base-url:https://api.bitget.com}") String baseUrl) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 查询历史资金费率（接口最多返回最近 90 天）。
     *
     * @param category 产品线，如 {@link #USDT_FUTURES}
     * @param symbol   交易对，如 BTCUSDT
     * @param limit    单页条数，官方上限 100
     * @param cursor   页码，从 1 开始。注意这个接口的 cursor 是<b>页码</b>，不是游标 ID
     * @return 该页的历史费率列表（按结算时间从新到旧），无数据时返回空列表
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public List<FundingRatePoint> historyFundingRate(String category, String symbol, int limit, int cursor) {
        BitgetResponse<HistoryData> resp = client.get()
                .uri(uri -> uri.path("/api/v3/market/history-fund-rate")
                        .queryParam("category", category)
                        .queryParam("symbol", symbol)
                        .queryParam("limit", limit)
                        .queryParam("cursor", cursor)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<BitgetResponse<HistoryData>>() {});
        if (resp == null || !OK.equals(resp.code()) || resp.data() == null) {
            throw new BitgetApiException("history-fund-rate 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp.data().resultList() == null ? List.of() : resp.data().resultList();
    }

    /**
     * 查询实时资金费率，含结算周期和下次结算时间。
     *
     * @param category 产品线，如 {@link #USDT_FUTURES}
     * @param symbol   交易对，如 BTCUSDT（与 category 不可同时为空）
     * @return 实时费率列表
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public List<CurrentFundingRate> currentFundingRate(String category, String symbol) {
        BitgetResponse<List<CurrentFundingRate>> resp = client.get()
                .uri(uri -> uri.path("/api/v3/market/current-fund-rate")
                        .queryParam("category", category)
                        .queryParam("symbol", symbol)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<BitgetResponse<List<CurrentFundingRate>>>() {});
        if (resp == null || !OK.equals(resp.code()) || resp.data() == null) {
            throw new BitgetApiException("current-fund-rate 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp.data();
    }

    /**
     * 查询某个产品线下的交易对信息（含合约的挂单 / 吃单费率）。
     *
     * <p>注意：现货不返回费率字段（官方说明"仅合约返回"），现货费率要用私有接口
     * {@code /api/v3/account/fee-rate}（需要 API Key）。
     *
     * @param category 产品线，如 {@link #USDT_FUTURES} 或 {@link #SPOT}
     * @return 交易对列表
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public List<InstrumentInfo> instruments(String category) {
        return instruments(category, false);
    }

    /**
     * 查询**模拟盘**支持的交易对清单。
     *
     * <p>实测：公开产品接口加上 {@code paptrading: 1} 头就会返回模拟盘清单（无需签名）。
     * 模拟盘只覆盖少数币种，同步篮子时要单独拉这份名单，避免对模拟盘不支持的币下单。
     *
     * @param category 产品线
     * @return 模拟盘支持该产品线的交易对列表
     */
    public List<InstrumentInfo> demoInstruments(String category) {
        return instruments(category, true);
    }

    private List<InstrumentInfo> instruments(String category, boolean demo) {
        BitgetResponse<List<InstrumentInfo>> resp = client.get()
                .uri(uri -> uri.path("/api/v3/market/instruments")
                        .queryParam("category", category)
                        .build())
                .headers(h -> {
                    // 加这个头会返回模拟盘的支持清单（公开接口，无需签名）
                    if (demo) {
                        h.set("paptrading", "1");
                    }
                })
                .retrieve()
                .body(new ParameterizedTypeReference<BitgetResponse<List<InstrumentInfo>>>() {});
        if (resp == null || !OK.equals(resp.code()) || resp.data() == null) {
            throw new BitgetApiException("instruments 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp.data();
    }

    /**
     * 查询某个产品线下所有交易对的实时行情（含 24 小时成交额，用于筛选目标币种池）。
     *
     * @param category 产品线，如 {@link #USDT_FUTURES}
     * @return 行情列表
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public List<Ticker> tickers(String category) {
        BitgetResponse<List<Ticker>> resp = client.get()
                .uri(uri -> uri.path("/api/v3/market/tickers")
                        .queryParam("category", category)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<BitgetResponse<List<Ticker>>>() {});
        if (resp == null || !OK.equals(resp.code()) || resp.data() == null) {
            throw new BitgetApiException("tickers 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp.data();
    }

    /**
     * 查询各币种的保证金折扣率（决定现货能抵多少保证金）。
     *
     * @return 每个币种一条，含分档的折扣率列表
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public List<DiscountRate> discountRates() {
        BitgetResponse<List<DiscountRate>> resp = client.get()
                .uri(uri -> uri.path("/api/v3/market/discount-rate").build())
                .retrieve()
                .body(new ParameterizedTypeReference<BitgetResponse<List<DiscountRate>>>() {});
        if (resp == null || !OK.equals(resp.code()) || resp.data() == null) {
            throw new BitgetApiException("discount-rate 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp.data();
    }

    /**
     * 查询订单簿深度（用于按盘口算"滑点上限内能吃多少量"）。
     *
     * @param category 产品线
     * @param symbol   交易对
     * @param limit    深度档数
     * @return 订单簿，asks 为卖档（价格升序），bids 为买档（价格降序），每项 [价格, 数量]
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public OrderBook orderBook(String category, String symbol, int limit) {
        return orderBook(category, symbol, limit, false);
    }

    /**
     * 查询**模拟盘**的订单簿。
     *
     * <p>为什么需要单独查：实测发现模拟盘的部分现货报价不可信（ETH 卖一 54500 而市价 3822、
     * SOL 偏离 55%），这类币在模拟盘永远成交不了，必须在下单前识别出来并排除。
     *
     * @param category 产品线
     * @param symbol   交易对
     * @param limit    深度档数
     * @return 模拟盘订单簿
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public OrderBook demoOrderBook(String category, String symbol, int limit) {
        return orderBook(category, symbol, limit, true);
    }

    private OrderBook orderBook(String category, String symbol, int limit, boolean demo) {
        BitgetResponse<OrderBook> resp = client.get()
                .uri(uri -> uri.path("/api/v3/market/orderbook")
                        .queryParam("category", category)
                        .queryParam("symbol", symbol)
                        .queryParam("limit", limit)
                        .build())
                .headers(h -> {
                    if (demo) {
                        h.set("paptrading", "1");
                    }
                })
                .retrieve()
                .body(new ParameterizedTypeReference<BitgetResponse<OrderBook>>() {});
        if (resp == null || !OK.equals(resp.code()) || resp.data() == null) {
            throw new BitgetApiException("orderbook 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp.data();
    }

    // ================= 响应结构（与 Bitget 返回的 JSON 一一对应） =================

    /**
     * Bitget 的统一响应外壳。
     *
     * @param code        业务码，00000 表示成功
     * @param msg         提示信息
     * @param requestTime 服务器时间戳（毫秒）
     * @param data        业务数据，结构随接口不同
     * @param <T>         业务数据类型
     */
    public record BitgetResponse<T>(String code, String msg, Long requestTime, T data) {
    }

    /**
     * 历史资金费率的 data 结构。
     *
     * @param resultList 费率列表
     */
    public record HistoryData(List<FundingRatePoint> resultList) {
    }

    /**
     * 历史资金费率的一条记录。
     *
     * @param symbol               交易对
     * @param fundingRate          资金费率（小数，0.0001 表示 0.01%）
     * @param fundingRateTimestamp 结算时间（毫秒时间戳，入库前转成可读时间）
     */
    public record FundingRatePoint(String symbol, String fundingRate, String fundingRateTimestamp) {
    }

    /**
     * 实时资金费率的一条记录。
     *
     * @param symbol                 交易对
     * @param fundingRate            当前资金费率（小数）
     * @param fundingRateInterval    结算周期，单位小时（1 / 2 / 4 / 8）
     * @param nextUpdate             下次结算时间（毫秒时间戳）
     * @param minFundingRate         资金费率下限
     * @param maxFundingRate         资金费率上限
     * @param cashDividend           现金派息（RWA 股票合约用，普通币为空）
     * @param cashDividendNextUpdate 现金派息的下次更新时间
     */
    public record CurrentFundingRate(String symbol, String fundingRate, String fundingRateInterval,
                                     String nextUpdate, String minFundingRate, String maxFundingRate,
                                     String cashDividend, String cashDividendNextUpdate) {
    }

    /**
     * 交易对信息（只列我们关心的字段）。
     *
     * @param category     产品线
     * @param symbol       交易对
     * @param baseCoin     基础币
     * @param quoteCoin    计价币
     * @param makerFeeRate 挂单费率（仅合约返回）
     * @param takerFeeRate 吃单费率（仅合约返回）
     * @param minOrderQty  最小下单数量
     * @param maxOrderQty  单笔最大下单数量（0 表示不限）
     * @param pricePrecision    价格精度（小数位数）
     * @param quantityPrecision 数量精度（小数位数）
     * @param quotePrecision    市价下单的计价精度（小数位数）
     * @param priceMultiplier   价格乘数（合约）
     * @param quantityMultiplier 数量乘数（合约）
     * @param type         合约类型（如 perpetual 永续）
     * @param isRwa        是否 RWA 交易对（yes / no，仅现货返回）
     * @param isReality    是否 Reality 股票代币（yes / no，仅现货返回）
     */
    public record InstrumentInfo(String category, String symbol, String baseCoin, String quoteCoin,
                                 String makerFeeRate, String takerFeeRate, String minOrderQty,
                                 String maxOrderQty, String pricePrecision, String quantityPrecision,
                                 String quotePrecision, String priceMultiplier, String quantityMultiplier,
                                 String type, String isRwa, String isReality) {
    }

    /**
     * 行情快照（只列我们关心的字段）。
     *
     * @param symbol      交易对
     * @param lastPrice   最新价
     * @param turnover24h 24 小时成交额（计价币，如 USDT）
     * @param volume24h   24 小时成交量（基础币）
     * @param fundingRate 当前资金费率（仅合约）
     * @param ts          行情时间（毫秒时间戳）
     */
    public record Ticker(String symbol, String lastPrice, String turnover24h, String volume24h,
                         String fundingRate, String ts) {
    }

    /**
     * 一个币种的保证金折扣率。
     *
     * @param coin 币种
     * @param list 分档折扣率（tierStartValue 为该档起始估值，discountRate 为折扣率）
     */
    public record DiscountRate(String coin, List<DiscountTier> list) {
    }

    /**
     * 折扣率的一档。
     *
     * @param tierStartValue 该档起始估值
     * @param discountRate   折扣率（0~1，越大越安全）
     */
    public record DiscountTier(String tierStartValue, String discountRate) {
    }

    /**
     * 订单簿。
     *
     * @param asks 卖档（价格升序），每项 [价格, 数量]
     * @param bids 买档（价格降序），每项 [价格, 数量]
     * @param ts   生成时间（毫秒）
     */
    public record OrderBook(@JsonProperty("a") List<List<String>> asks,
                            @JsonProperty("b") List<List<String>> bids,
                            @JsonProperty("ts") String ts) {
    }
}
