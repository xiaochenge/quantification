package com.quantification.exchange;

import com.quantification.bitget.BitgetApiException;
import com.quantification.bitget.BitgetPrivateClient.AccountAssets;
import com.quantification.bitget.BitgetPrivateClient.AssetCoin;
import com.quantification.bitget.BitgetPrivateClient.FeeRateItem;
import com.quantification.bitget.BitgetPrivateClient.OrderInfo;
import com.quantification.bitget.BitgetPrivateClient.OrderResult;
import com.quantification.bitget.BitgetPrivateClient.Position;
import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.FundingRatePoint;
import com.quantification.bitget.BitgetPublicClient.OrderBook;
import com.quantification.bitget.BitgetPublicClient.Ticker;
import com.quantification.entity.FundingIncome;
import com.quantification.entity.FundingRateHistory;
import com.quantification.entity.Instrument;
import com.quantification.entity.MockAccount;
import com.quantification.entity.MockPosition;
import com.quantification.entity.TradeFill;
import com.quantification.entity.TradeOrder;
import com.quantification.entity.WatchCoin;
import com.quantification.mapper.FundingIncomeMapper;
import com.quantification.mapper.FundingRateHistoryMapper;
import com.quantification.mapper.InstrumentMapper;
import com.quantification.mapper.MockAccountMapper;
import com.quantification.mapper.MockPositionMapper;
import com.quantification.mapper.TradeFillMapper;
import com.quantification.mapper.TradeOrderMapper;
import com.quantification.mapper.WatchCoinMapper;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 自建 mock 交易所（模块 9 的核心）。
 *
 * <p>它替交易所做四件事：
 * <ol>
 *   <li>行情、费率、盘口<b>全部读真实接口</b>（{@link BitgetPublicClient}）；</li>
 *   <li>下单 / 撤单 / 查单在<b>本地撮合</b>：按真实盘口深度算成交均价与滑点（{@link MatchEngine}），
 *       限价 FOK 吃不下就整笔撤销，精度、余额、保证金不足一律拒单；</li>
 *   <li>账户（现金、现货、永续空头）由本地账本维护，落库到 {@code mock_account} 与
 *       {@code mock_position}，<b>程序重启后能接着上一次的仓位继续跑</b>；</li>
 *   <li>资金费自己结算：真实费率 × 真实仓位，写入 {@code funding_income}。</li>
 * </ol>
 *
 * <p><b>安全边界</b>：本类不注入 {@code BitgetPrivateClient}，因此不可能发出任何真实交易请求。
 *
 * <p><b>与实盘有意的差异（简化）</b>：
 * <ol>
 *   <li>平仓单（卖现货 / 买回永续）数量超过持仓时按"减到零"处理并打 WARN，而实盘会直接拒单——
 *       这样正常轮动不会因为一位小数误差被误判成"只平了一条腿"；</li>
 *   <li>开空仓的保证金校验按"1 倍可用担保"粗算，不做逐档保证金计算；</li>
 *   <li>维持保证金率用配置的固定值折算（{@code simulation.maintenance-margin-rate}）；</li>
 *   <li>资金费名义值用结算时刻的真实最新价近似（不落 K 线）。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "simulation.enabled", havingValue = "true")
public class MockExchangeGateway implements ExchangeGateway {

    private static final Logger log = LoggerFactory.getLogger(MockExchangeGateway.class);

    /** 模拟数据的 source 标记（落在 trade_order / trade_fill / funding_income / 净值快照上）。 */
    public static final String SOURCE = "mock";

    /** 模拟账户在 {@code mock_account} 表里的固定标识（单行表）。 */
    private static final String ACCOUNT_KEY = "mock";

    /** 统一账户的结算币种。 */
    private static final String COIN = "USDT";

    /** 时间统一按 Asia/Shanghai 处理。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 内部状态（现金 / 数量）的保留小数位。 */
    private static final int STATE_SCALE = 18;

    /** 行情客户端：盘口与最新价都从这里取（公共接口，不需要密钥）。 */
    private final BitgetPublicClient publicClient;

    /** 交易对规则：下单前校验精度 / 最小量 / 乘数，与实盘用同一份落库数据。 */
    private final InstrumentMapper instrumentMapper;

    /** 监控篮子：取保证金折扣率算有效权益。 */
    private final WatchCoinMapper watchCoinMapper;

    /** 模拟账户现金。 */
    private final MockAccountMapper accountMapper;

    /** 模拟持仓。 */
    private final MockPositionMapper positionMapper;

    /** 订单账本。 */
    private final TradeOrderMapper tradeOrderMapper;

    /** 成交明细账本。 */
    private final TradeFillMapper tradeFillMapper;

    /** 资金费入账。 */
    private final FundingIncomeMapper fundingIncomeMapper;

    /** 历史资金费率：结算点用它的真实数据。 */
    private final FundingRateHistoryMapper historyMapper;

    /** 初始模拟资金（USDT）。 */
    private final BigDecimal initialUsdt;

    /** 维持保证金率假设（算 mgnRatio 用）。 */
    private final BigDecimal maintenanceMarginRate;

    /** 现货吃单费率（与 funding-yield 段同源，保证"模拟扣的费"和"成本口径"一致）。 */
    private final BigDecimal spotTakerFeeRate;

    /** 合约吃单费率。 */
    private final BigDecimal perpTakerFeeRate;

    /** 市价单最多吃多少档盘口。 */
    private final int depthLimit;

    /** 最新价缓存时长（毫秒）：一轮评估里会反复读同一个价格，缓存能少打几次接口。 */
    private final long tickerCacheMs;

    /** 账户状态并发锁：策略循环、资金费结算、净值快照可能同时读写，必须串行。 */
    private final Object lock = new Object();

    /** USDT 现金余额（内存态，落库对应 mock_account）。 */
    private BigDecimal cash = BigDecimal.ZERO;

    /** 各币持仓（内存态，落库对应 mock_position）。 */
    private final Map<String, Holding> holdings = new LinkedHashMap<>();

    /**
     * 最新价缓存（一次请求拿全部永续交易对）。
     *
     * <p>{@code volatile}：只在 {@link #priceSnapshot()} 里刷新，可能被多个线程读到。
     * 这里追求的是"读到一个完整的旧快照"，不追求完全互斥（重复刷新一次无害）。
     */
    private volatile Map<String, BigDecimal> tickerCache = Map.of();

    /** 最新价缓存时间（毫秒）。 */
    private volatile long tickerCacheAt = 0L;

    public MockExchangeGateway(BitgetPublicClient publicClient,
                               InstrumentMapper instrumentMapper,
                               WatchCoinMapper watchCoinMapper,
                               MockAccountMapper accountMapper,
                               MockPositionMapper positionMapper,
                               TradeOrderMapper tradeOrderMapper,
                               TradeFillMapper tradeFillMapper,
                               FundingIncomeMapper fundingIncomeMapper,
                               FundingRateHistoryMapper historyMapper,
                               @Value("${simulation.initial-usdt:10000}") BigDecimal initialUsdt,
                               @Value("${simulation.maintenance-margin-rate:0.02}") BigDecimal maintenanceMarginRate,
                               @Value("${funding-yield.spot-taker-fee-rate:0.0006}") BigDecimal spotTakerFeeRate,
                               @Value("${funding-yield.perp-taker-fee-rate:0.000375}") BigDecimal perpTakerFeeRate,
                               @Value("${simulation.depth-limit:100}") int depthLimit,
                               @Value("${simulation.ticker-cache-ms:5000}") long tickerCacheMs) {
        this.publicClient = publicClient;
        this.instrumentMapper = instrumentMapper;
        this.watchCoinMapper = watchCoinMapper;
        this.accountMapper = accountMapper;
        this.positionMapper = positionMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.tradeFillMapper = tradeFillMapper;
        this.fundingIncomeMapper = fundingIncomeMapper;
        this.historyMapper = historyMapper;
        this.initialUsdt = initialUsdt;
        this.maintenanceMarginRate = maintenanceMarginRate;
        this.spotTakerFeeRate = spotTakerFeeRate;
        this.perpTakerFeeRate = perpTakerFeeRate;
        this.depthLimit = depthLimit;
        this.tickerCacheMs = tickerCacheMs;
    }

    /**
     * 启动时把上一次的模拟账户状态读回内存。
     *
     * <p>第一次运行（库里没有 mock_account）按配置的初始资金初始化。这保证"重启不会重置模拟收益"，
     * 与实盘的"重启对账接管"是同一个纪律。
     */
    @PostConstruct
    public void loadState() {
        MockAccount account = accountMapper.find(ACCOUNT_KEY);
        if (account == null) {
            cash = initialUsdt;
            persistAccount();
            log.info("自建 mock 首次运行：初始化模拟账户资金 {} USDT", initialUsdt.toPlainString());
        } else {
            cash = account.getUsdtBalance();
            log.info("自建 mock 恢复模拟账户：现金 {} USDT（初始 {} USDT）",
                    cash.toPlainString(), account.getInitialUsdt().toPlainString());
        }
        for (MockPosition row : positionMapper.findAll()) {
            Holding holding = new Holding(row.getBaseCoin(), row.getSpotSymbol(), row.getFuturesSymbol());
            holding.spotQty = row.getSpotQty() == null ? BigDecimal.ZERO : row.getSpotQty();
            holding.spotAvgPrice = row.getSpotAvgPrice();
            holding.perpQty = row.getPerpQty() == null ? BigDecimal.ZERO : row.getPerpQty();
            holding.perpAvgPrice = row.getPerpAvgPrice();
            holding.openedAt = row.getOpenedAt();
            holdings.put(holding.baseCoin, holding);
        }
        if (!holdings.isEmpty()) {
            log.warn("自建 mock 恢复出 {} 个已有持仓，按【模拟交易所为准】原则接管：", holdings.size());
            holdings.values().forEach(h -> log.warn("  {} 现货 {}，永续空 {}",
                    h.baseCoin, h.spotQty.toPlainString(), h.perpQty.toPlainString()));
        }
        log.info("自建 mock 已就绪：行情 / 费率 / 盘口读真实接口，下单只在本地模拟，不会动到真实资金");
    }

    @Override
    public TradeMode mode() {
        return TradeMode.MOCK;
    }

    @Override
    public boolean isConfigured() {
        // 自建 mock 不需要密钥：它自己就是交易所
        return true;
    }

    /**
     * 模拟账户权益（口径与文档 7.1 的强平公式一致）。
     *
     * <p>总权益 = 现金 + 现货市值 + 永续浮盈亏（空头为"开仓均价 − 最新价"）；
     * 有效权益（能当保证金的部分）= 现金 + 折扣率 × 现货市值 + 永续浮盈亏。
     */
    @Override
    public AccountAssets assets() {
        // 取价是网络调用，必须在锁外完成（见 priceSnapshot 注释）
        Map<String, BigDecimal> prices = priceSnapshot();
        synchronized (lock) {
            BigDecimal spotValue = BigDecimal.ZERO;
            BigDecimal effSpotValue = BigDecimal.ZERO;
            BigDecimal unrealisedPnl = BigDecimal.ZERO;
            BigDecimal positionValue = BigDecimal.ZERO;
            BigDecimal mmr = BigDecimal.ZERO;
            List<AssetCoin> coins = new ArrayList<>();
            coins.add(new AssetCoin(COIN, plain(cash), plain(cash), plain(cash)));

            for (Holding h : holdings.values()) {
                BigDecimal price = priceOf(prices, h.futuresSymbol);
                BigDecimal spot = h.spotQty.multiply(price);
                BigDecimal pnl = h.unrealisedPnl(price);
                BigDecimal notional = h.perpQty.multiply(price);
                spotValue = spotValue.add(spot);
                effSpotValue = effSpotValue.add(spot.multiply(discountRate(h.baseCoin)));
                unrealisedPnl = unrealisedPnl.add(pnl);
                positionValue = positionValue.add(notional);
                mmr = mmr.add(notional.multiply(maintenanceMarginRate));
                if (h.spotQty.signum() > 0) {
                    coins.add(new AssetCoin(h.baseCoin, plain(h.spotQty), plain(h.spotQty), plain(spot)));
                }
            }

            BigDecimal equity = cash.add(spotValue).add(unrealisedPnl);
            BigDecimal effEquity = cash.add(effSpotValue).add(unrealisedPnl);
            BigDecimal mgnRatio = effEquity.signum() > 0
                    ? mmr.divide(effEquity, 8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new AccountAssets(plain(equity), plain(effEquity), plain(mmr), plain(mgnRatio),
                    plain(positionValue), coins);
        }
    }

    /** @return 模拟持仓（只返回永续空头，与实盘查询口径一致） */
    @Override
    public List<Position> positions(String category, String symbol) {
        // 取价是网络调用，必须在锁外完成（见 priceSnapshot 注释）
        Map<String, BigDecimal> prices = priceSnapshot();
        synchronized (lock) {
            List<Position> result = new ArrayList<>();
            if (!BitgetPublicClient.USDT_FUTURES.equals(category)) {
                return result;
            }
            for (Holding h : holdings.values()) {
                if (h.perpQty.signum() <= 0) {
                    continue;
                }
                if (symbol != null && !symbol.isBlank() && !symbol.equals(h.futuresSymbol)) {
                    continue;
                }
                BigDecimal price = priceOf(prices, h.futuresSymbol);
                result.add(new Position(h.futuresSymbol, "short", plain(h.perpQty), plain(h.perpQty),
                        plain(h.perpAvgPrice), plain(h.unrealisedPnl(price)), "1", "crossed"));
            }
            return result;
        }
    }

    /**
     * 模拟的账户费率：只模拟吃单，maker 与 taker 填同一个值。
     *
     * <p>必须与撮合时实际扣的手续费同源（都取 {@code funding-yield.*-taker-fee-rate}），
     * 否则"记在 fee_rate 表里的成本"和"模拟扣掉的成本"会对不上。
     */
    @Override
    public List<FeeRateItem> allFeeRates(String category) {
        BigDecimal taker = BitgetPublicClient.SPOT.equals(category) ? spotTakerFeeRate : perpTakerFeeRate;
        List<FeeRateItem> items = new ArrayList<>();
        for (WatchCoin coin : watchCoinMapper.findEnabled()) {
            String symbol = BitgetPublicClient.SPOT.equals(category) ? coin.getSpotSymbol() : coin.getFuturesSymbol();
            items.add(new FeeRateItem(symbol, plain(taker), plain(taker)));
        }
        return items;
    }

    /**
     * 下单：本地撮合。
     *
     * <p>流程与真实交易所一致：幂等检查 → 规则校验 → 撮合 → 资金校验 → 落账。
     * 被拒或 FOK 撤销都会在 {@code trade_order} 里留一条记录，便于事后解释"为什么没成交"。
     */
    @Override
    public OrderResult placeOrder(Map<String, Object> fields) {
        String clientOid = text(fields.get("clientOid"));
        if (clientOid == null || clientOid.isBlank()) {
            throw new BitgetApiException("mock 下单必须带 clientOid（幂等与反查都靠它）");
        }
        // 幂等：同一个 clientOid 只允许成交一次（断网重试、程序重启都可能重放同一笔单）
        TradeOrder existing = tradeOrderMapper.findByClientOid(clientOid);
        if (existing != null) {
            log.warn("mock 收到重复的 clientOid={}（已存在状态 {}），按幂等处理，不重复成交",
                    clientOid, existing.getOrderStatus());
            if ("rejected".equals(existing.getOrderStatus())) {
                throw new BitgetApiException("订单此前已被拒（幂等重放）：" + existing.getRejectReason());
            }
            return new OrderResult(existing.getExchangeOrderId(), clientOid);
        }

        Request request = new Request(clientOid, text(fields.get("category")), text(fields.get("symbol")),
                text(fields.get("side")), text(fields.get("posSide")), text(fields.get("orderType")),
                text(fields.get("timeInForce")), number(fields.get("price")), number(fields.get("qty")));

        Instrument rule = instrumentMapper.find(request.category(), request.symbol());
        String problem = validateRules(rule, request.qty(), request.price());
        if (problem != null) {
            reject(request, problem);
        }

        // 平仓方向把数量收敛到现有持仓（见类注释第 1 条）
        BigDecimal qty = capToHolding(request, rule);
        if (qty == null || qty.signum() <= 0) {
            // 没有对应持仓还要平仓 → 必须在撮合前拒掉。
            // 否则"卖出一个没有的币"会凭空变成现金（模拟账本被写坏）。
            reject(request, "25202 没有可平的" + (request.isFutures() ? "空头仓位" : "现货仓位"));
        }
        List<List<String>> levels = levelsFor(request.category(), request.symbol(), request.isBuy());
        MatchEngine.Match match = "market".equalsIgnoreCase(request.orderType())
                ? MatchEngine.matchMarket(levels, qty, request.isBuy(), depthLimit)
                : MatchEngine.matchLimitFok(levels, qty, request.price(), request.isBuy());

        if (!match.isFilled()) {
            recordOrder(request, rule, qty, BigDecimal.ZERO, null, "cancelled", match.reason());
            log.warn("mock 订单未成交（{}）：{} {} {} qty={} price={}",
                    match.reason(), request.category(), request.symbol(), request.side(), qty, request.price());
            return new OrderResult("mock-" + clientOid, clientOid);
        }

        // 下面两件事都是网络调用，必须在锁外做（见 priceSnapshot 注释）。
        // 平空（买回永续）前先补拉该币最近的历史费率：否则"币掉出篮子后最后一段结算点没采到"
        // 会让资金费漏结。
        if (request.isFutures() && request.isBuy()) {
            backfillFundingHistory(request.symbol());
        }
        Map<String, BigDecimal> prices = priceSnapshot();

        synchronized (lock) {
            String fundsProblem = validateFunds(request, rule, match, prices);
            if (fundsProblem != null) {
                reject(request, fundsProblem);
            }
            applyFill(request, rule, qty, match, prices);
        }
        return new OrderResult("mock-" + clientOid, clientOid);
    }

    /**
     * 撤单。
     *
     * <p>自建 mock 的撮合是"即时成交或即时撤销"，正常不会留下挂单；这里仍按真实语义实现，
     * 供将来接入挂单类型时使用。
     */
    @Override
    public OrderResult cancelOrder(Map<String, Object> fields) {
        String clientOid = text(fields.get("clientOid"));
        TradeOrder order = clientOid == null || clientOid.isBlank()
                ? null : tradeOrderMapper.findByClientOid(clientOid);
        if (order == null || !"live".equals(order.getOrderStatus())) {
            throw new BitgetApiException("mock 撤单失败：订单不存在或已是终态");
        }
        tradeOrderMapper.updateStatus(order.getClientOid(), "cancelled", "人工撤单",
                order.getCumExecQty(), order.getAvgPrice());
        return new OrderResult(order.getExchangeOrderId(), order.getClientOid());
    }

    /** @return 订单详情（模拟撮合是即时终态，直接读本地账本） */
    @Override
    public OrderInfo orderInfo(String orderId, String clientOid) {
        TradeOrder order = clientOid == null || clientOid.isBlank()
                ? null : tradeOrderMapper.findByClientOid(clientOid);
        if (order == null) {
            return null;
        }
        return new OrderInfo(order.getExchangeOrderId(), order.getClientOid(), order.getSymbol(),
                order.getOrderStatus(), plain(order.getPrice()), plain(order.getQty()),
                plain(order.getCumExecQty()), plain(order.getAvgPrice()), order.getSide(), order.getPosSide(),
                order.getExchangeCreatedTime() == null ? null
                        : String.valueOf(order.getExchangeCreatedTime().atZone(ZONE).toInstant().toEpochMilli()));
    }

    /** @return 未成交订单：自建 mock 不留挂单，恒为空 */
    @Override
    public List<OrderInfo> unfilledOrders(String category) {
        return List.of();
    }

    /**
     * 按真实费率 × 真实仓位结算资金费。
     *
     * <p>规则：做空永续，费率为正时收资金费（多头付给空头），为负时付出。
     * 每个结算点只入账一次：先按唯一键 {@code (source, symbol, settlement_time)} 插库，
     * 插入成功才真的加钱——重启、任务重跑都不会重复入账。
     *
     * <p>结算点取自 {@code funding_rate_history}（采集任务最多 6 小时延迟），
     * 每次只补"库里已有、且还没结过"的结算点。
     */
    @Override
    public void settleFunding() {
        // 取价是网络调用，必须在锁外完成（见 priceSnapshot 注释）
        Map<String, BigDecimal> prices = priceSnapshot();
        synchronized (lock) {
            for (Holding holding : holdings.values()) {
                if (holding.perpQty.signum() > 0) {
                    settleHolding(holding, prices);
                }
            }
        }
    }

    /** @return 模拟账户状态（后台只读展示用） */
    public AccountState state() {
        // 取价是网络调用，必须在锁外完成（见 priceSnapshot 注释）
        Map<String, BigDecimal> prices = priceSnapshot();
        synchronized (lock) {
            List<HoldingView> views = new ArrayList<>();
            for (Holding h : holdings.values()) {
                BigDecimal price = priceOf(prices, h.futuresSymbol);
                views.add(new HoldingView(h.baseCoin, trim(h.spotQty), trim(h.spotAvgPrice),
                        trim(h.perpQty), trim(h.perpAvgPrice), trim(price),
                        trim(h.unrealisedPnl(price)), trim(h.spotUnrealisedPnl(price)), h.openedAt));
            }
            return new AccountState(trim(cash), trim(initialUsdt), views);
        }
    }

    /**
     * 各币的建仓时间（锁内只读内存，不碰网络）。
     *
     * <p>给策略的"最短持有期"用：刚换过去的仓位不该下一小时就被换回来。
     *
     * @return 交易对 → 建仓时间
     */
    @Override
    public Map<String, LocalDateTime> positionOpenedAt() {
        synchronized (lock) {
            Map<String, LocalDateTime> result = new LinkedHashMap<>();
            for (Holding h : holdings.values()) {
                if (h.openedAt != null && (h.perpQty.signum() > 0 || h.spotQty.signum() > 0)) {
                    result.put(h.futuresSymbol, h.openedAt);
                }
            }
            return result;
        }
    }

    // ================= 内部：撮合与账务 =================

    /**
     * 落一笔成交：改账户状态 + 写订单与成交明细。
     *
     * @param request 原始下单请求
     * @param rule    交易对规则
     * @param qty     实际下单数量（平仓方向已收敛）
     * @param match   撮合结果
     * @param prices  最新价快照（锁外取的，见 {@link #priceSnapshot()}）
     */
    private void applyFill(Request request, Instrument rule, BigDecimal qty, MatchEngine.Match match,
                           Map<String, BigDecimal> prices) {
        BigDecimal feeRate = request.isFutures() ? perpTakerFeeRate : spotTakerFeeRate;
        BigDecimal price = match.avgPrice();
        BigDecimal notional = match.filledNotional();
        BigDecimal fee = notional.multiply(feeRate).setScale(8, RoundingMode.HALF_UP);
        LocalDateTime now = LocalDateTime.now(ZONE);
        String baseCoin = baseCoinOf(rule, request.symbol());
        Holding holding = holdings.computeIfAbsent(baseCoin,
                key -> new Holding(key, key + COIN, key + COIN));
        String tradeSide;
        BigDecimal execPnl = null;

        if (request.isFutures()) {
            if (request.isBuy()) {
                // 买回永续 = 平空：把(开仓均价 − 平仓价)兑现成现金，再扣手续费
                tradeSide = "close";
                // 结算应计资金费。补拉历史费率（网络）已由 placeOrder 在锁外完成，
                // 这里只查库 + 用价格快照，不碰网络。
                settleHolding(holding, prices);
                BigDecimal closing = qty.min(holding.perpQty);
                if (holding.perpAvgPrice != null) {
                    execPnl = holding.perpAvgPrice.subtract(price).multiply(closing)
                            .setScale(8, RoundingMode.HALF_UP);
                }
                cash = cash.add(execPnl == null ? BigDecimal.ZERO : execPnl).subtract(fee);
                holding.perpQty = holding.perpQty.subtract(closing);
                if (holding.perpQty.signum() <= 0) {
                    holding.perpQty = BigDecimal.ZERO;
                    holding.perpAvgPrice = null;
                }
            } else {
                // 卖出永续 = 开空 / 加空：现金不动（现货抵保证金），只扣手续费
                tradeSide = "open";
                cash = cash.subtract(fee);
                BigDecimal newQty = holding.perpQty.add(qty);
                if (holding.perpAvgPrice == null || holding.perpQty.signum() <= 0) {
                    holding.perpAvgPrice = price;
                } else {
                    holding.perpAvgPrice = holding.perpQty.multiply(holding.perpAvgPrice).add(notional)
                            .divide(newQty, STATE_SCALE, RoundingMode.HALF_UP);
                }
                holding.perpQty = newQty;
            }
        } else if (request.isBuy()) {
            tradeSide = "open";
            cash = cash.subtract(notional).subtract(fee);
            BigDecimal newSpotQty = holding.spotQty.add(qty);
            if (holding.spotAvgPrice == null || holding.spotQty.signum() <= 0) {
                holding.spotAvgPrice = price;
            } else {
                holding.spotAvgPrice = holding.spotQty.multiply(holding.spotAvgPrice).add(notional)
                        .divide(newSpotQty, STATE_SCALE, RoundingMode.HALF_UP);
            }
            holding.spotQty = newSpotQty;
        } else {
            tradeSide = "close";
            // 现货平仓的已实现盈亏 = (卖出价 − 开仓均价) × 卖出量，落库便于盈亏归因
            BigDecimal closingSpot = qty.min(holding.spotQty);
            if (holding.spotAvgPrice != null) {
                execPnl = price.subtract(holding.spotAvgPrice).multiply(closingSpot)
                        .setScale(8, RoundingMode.HALF_UP);
            }
            cash = cash.add(notional).subtract(fee);
            holding.spotQty = holding.spotQty.subtract(closingSpot).max(BigDecimal.ZERO);
            if (holding.spotQty.signum() <= 0) {
                holding.spotAvgPrice = null;
            }
        }

        if (holding.openedAt == null && (holding.spotQty.signum() > 0 || holding.perpQty.signum() > 0)) {
            holding.openedAt = now;
        }
        if (holding.spotQty.signum() <= 0 && holding.perpQty.signum() <= 0) {
            holdings.remove(baseCoin);
            positionMapper.delete(baseCoin);
        } else {
            persistHolding(holding);
        }
        persistAccount();

        TradeOrder order = recordOrder(request, rule, qty, match.filledQty(), match.avgPrice(), "filled", null);
        order.setFee(fee);
        order.setFeeCoin(COIN);

        TradeFill fill = new TradeFill();
        fill.setSource(SOURCE);
        fill.setDedupKey(SOURCE + ":" + request.clientOid());
        fill.setOrderId(order.getId());
        fill.setClientOid(request.clientOid());
        fill.setLeg(request.isFutures() ? "perp" : "spot");
        fill.setSymbol(request.symbol());
        fill.setCategory(request.category());
        fill.setSide(request.side());
        fill.setTradeSide(tradeSide);
        fill.setOrderType(request.orderType());
        fill.setExecPrice(price);
        fill.setExecQty(match.filledQty());
        fill.setExecValue(notional);
        fill.setTradeScope("taker");
        fill.setFee(fee);
        fill.setFeeCoin(COIN);
        fill.setSlippage(match.slippage());
        fill.setExecPnl(execPnl);
        fill.setCreatedTime(now);
        tradeFillMapper.insertIgnore(fill);

        log.info("mock 成交 {} {} {} {}：数量 {}，均价 {}，名义 {}，滑点 {}，手续费 {} USDT",
                request.category(), request.symbol(), request.side(), tradeSide, plain(match.filledQty()),
                plain(price), plain(notional),
                match.slippage() == null ? "-" : plain(match.slippage()), plain(fee));
    }

    /**
     * 结算某个持仓的资金费（增量补结算点）。
     *
     * @param holding 持仓
     */
    /**
     * 补拉某个币最近的历史费率（幂等）。
     *
     * <p>平仓前调用：币掉出篮子后，定时历史采集不再覆盖它，但平仓前必须把"最后一次结算点"
     * 补进库，否则 {@link #settleHolding} 找不到结算点、资金费就会漏结。
     */
    private void backfillFundingHistory(String symbol) {
        try {
            for (int page = 1; page <= 2; page++) {
                List<FundingRatePoint> points =
                        publicClient.historyFundingRate(BitgetPublicClient.USDT_FUTURES, symbol, 100, page);
                if (points.isEmpty()) {
                    break;
                }
                for (FundingRatePoint point : points) {
                    FundingRateHistory row = new FundingRateHistory();
                    row.setSymbol(symbol);
                    row.setCategory(BitgetPublicClient.USDT_FUTURES);
                    row.setFundingRate(number(point.fundingRate()));
                    row.setFundingTime(toLocalDateTime(point.fundingRateTimestamp()));
                    historyMapper.insertIgnore(row);
                }
            }
        } catch (Exception e) {
            // 补拉失败只记日志：资金费结算本身是幂等的，下次还会重试
            log.warn("平仓前补拉历史费率失败 {}：{}", symbol, e.getMessage());
        }
    }

    /** 交易所毫秒时间戳转本地可读时间（仅在本类边界转换一次）。 */
    private static LocalDateTime toLocalDateTime(String epochMillis) {
        if (epochMillis == null || epochMillis.isBlank()) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(epochMillis)), ZONE);
    }

    private void settleHolding(Holding holding, Map<String, BigDecimal> prices) {
        try {
            LocalDateTime from = fundingIncomeMapper.findLastSettlementTime(SOURCE, holding.futuresSymbol);
            if (holding.openedAt != null && (from == null || holding.openedAt.isAfter(from))) {
                from = holding.openedAt;
            }
            if (from == null) {
                return;
            }
            List<FundingRateHistory> points =
                    historyMapper.findSettlementsBetween(holding.futuresSymbol, from, LocalDateTime.now(ZONE));
            if (points.isEmpty()) {
                return;
            }
            BigDecimal price = priceOf(prices, holding.futuresSymbol);
            if (price.signum() <= 0) {
                log.warn("mock 资金费结算跳过 {}：取不到最新价", holding.futuresSymbol);
                return;
            }
            for (FundingRateHistory point : points) {
                BigDecimal rate = point.getFundingRate();
                BigDecimal notional = holding.perpQty.multiply(price);
                // 做空永续：费率为正时收资金费，为负时付出
                BigDecimal amount = rate.multiply(notional).setScale(8, RoundingMode.HALF_UP);
                FundingIncome row = new FundingIncome();
                row.setSource(SOURCE);
                row.setSymbol(holding.futuresSymbol);
                row.setCategory(BitgetPublicClient.USDT_FUTURES);
                row.setFundingRate(rate);
                row.setPositionQty(holding.perpQty);
                row.setMarkPrice(price);
                row.setFundingAmount(amount);
                row.setCoin(COIN);
                row.setSettlementTime(point.getFundingTime());
                if (fundingIncomeMapper.insertIgnore(row) == 1) {
                    cash = cash.add(amount);
                    persistAccount();
                    log.info("mock 资金费入账 {} 结算点 {}：费率 {}，仓位 {}，名义 {}，金额 {} USDT",
                            holding.futuresSymbol, point.getFundingTime(), plain(rate), plain(holding.perpQty),
                            plain(notional), plain(amount));
                }
            }
        } catch (Exception e) {
            // 结算必须"尽最大努力但不影响交易"：失败只记日志，下次再补（幂等）。
            log.warn("mock 资金费结算异常 {}（下个周期重试）：{}", holding.futuresSymbol, e.getMessage());
        }
    }

    /**
     * 下单前校验交易对规则（与实盘同一份落库数据，不满足就拒单）。
     *
     * @param rule  规则
     * @param qty   数量
     * @param price 价格（市价单为 null）
     * @return 拒绝原因；通过返回 null
     */
    private String validateRules(Instrument rule, BigDecimal qty, BigDecimal price) {
        if (rule == null) {
            return "数据库里没有该交易对的规则，拒绝下单";
        }
        if (qty == null || qty.signum() <= 0) {
            return "40808 数量非法";
        }
        int qtyScale = rule.getQuantityPrecision() == null ? 0 : rule.getQuantityPrecision();
        if (qty.scale() > qtyScale) {
            return "40808 数量精度不符（允许 " + qtyScale + " 位，实际 " + qty.scale() + " 位）";
        }
        if (rule.getMinOrderQty() != null && qty.compareTo(rule.getMinOrderQty()) < 0) {
            return "40808 数量小于最小下单量 " + rule.getMinOrderQty().toPlainString();
        }
        if (rule.getMaxOrderQty() != null && rule.getMaxOrderQty().signum() > 0
                && qty.compareTo(rule.getMaxOrderQty()) > 0) {
            return "40808 数量超过单笔最大下单量 " + rule.getMaxOrderQty().toPlainString();
        }
        if (rule.getQuantityMultiplier() != null && rule.getQuantityMultiplier().signum() > 0
                && qty.remainder(rule.getQuantityMultiplier()).signum() != 0) {
            return "40808 数量必须是数量乘数 " + rule.getQuantityMultiplier().toPlainString() + " 的整数倍";
        }
        if (price != null) {
            int priceScale = rule.getPricePrecision() == null ? 0 : rule.getPricePrecision();
            if (price.scale() > priceScale) {
                return "40808 价格精度不符（允许 " + priceScale + " 位，实际 " + price.scale() + " 位）";
            }
        }
        return null;
    }

    /**
     * 撮合后校验资金 / 持仓（对应实盘的"余额不足""保证金不足"）。
     *
     * <p>必须用<b>实际成交名义额</b>校验，而不是委托名义额——否则小额多次成交会被误判。
     *
     * @return 拒绝原因；通过返回 null
     */
    private String validateFunds(Request request, Instrument rule, MatchEngine.Match match,
                                 Map<String, BigDecimal> prices) {
        BigDecimal fee = match.filledNotional()
                .multiply(request.isFutures() ? perpTakerFeeRate : spotTakerFeeRate);
        if (request.isFutures()) {
            if (request.isBuy()) {
                return null;   // 平空：数量已在 capToHolding 收敛到持仓范围
            }
            BigDecimal collateral = collateralValue(prices);
            if (match.filledNotional().add(fee).compareTo(collateral) > 0) {
                return "25202 保证金不足：开空需要 " + plain(match.filledNotional().add(fee))
                        + "，可用担保 " + plain(collateral);
            }
            return null;
        }
        if (!request.isBuy()) {
            return null;   // 卖现货：数量已在 capToHolding 收敛到持仓范围
        }
        if (match.filledNotional().add(fee).compareTo(cash) > 0) {
            return "25202 余额不足：买入需要 " + plain(match.filledNotional().add(fee))
                    + "，现金 " + plain(cash);
        }
        return null;
    }

    /**
     * 可用的担保价值（开空仓的额度）。
     *
     * @return 现金 + 折扣率 × 现货市值 + 空头浮盈亏
     */
    private BigDecimal collateralValue(Map<String, BigDecimal> prices) {
        BigDecimal collateral = cash;
        for (Holding h : holdings.values()) {
            BigDecimal price = priceOf(prices, h.futuresSymbol);
            collateral = collateral
                    .add(h.spotQty.multiply(price).multiply(discountRate(h.baseCoin)))
                    .add(h.unrealisedPnl(price));
        }
        return collateral;
    }

    /**
     * 平仓方向把数量收敛到现有持仓。
     *
     * <p>见类注释：这是自建 mock 有意的宽容（实盘会拒单），避免正常轮动因为一位小数误差
     * 被误判成"只平了一条腿"。收敛时打 WARN 留痕。
     *
     * @return 实际下单数量
     */
    private BigDecimal capToHolding(Request request, Instrument rule) {
        String baseCoin = baseCoinOf(rule, request.symbol());
        synchronized (lock) {
            Holding holding = holdings.get(baseCoin);
            if (holding == null) {
                // 没有这个币的持仓：开仓方向无所谓（下面按撮合结果再校验资金），
                // 平仓方向则必须收敛成 0，由调用方直接拒单（绝不能凭空卖出 → 凭空变出资金）
                return request.isReduce() ? BigDecimal.ZERO : request.qty();
            }
            if (request.isFutures() && request.isBuy() && request.qty().compareTo(holding.perpQty) > 0) {
                log.warn("mock 平空数量 {} 超过持仓 {}，按持仓数量成交", request.qty(), holding.perpQty);
                return holding.perpQty;
            }
            if (!request.isFutures() && !request.isBuy() && request.qty().compareTo(holding.spotQty) > 0) {
                log.warn("mock 卖出现货数量 {} 超过持仓 {}，按持仓数量成交", request.qty(), holding.spotQty);
                return holding.spotQty;
            }
            return request.qty();
        }
    }

    /** 记录一笔被拒订单并抛出异常（调用方只看到交易所风格的报错）。 */
    private void reject(Request request, String reason) {
        recordOrder(request, instrumentMapper.find(request.category(), request.symbol()),
                request.qty(), BigDecimal.ZERO, null, "rejected", reason);
        log.error("mock 拒单 {} {} {} qty={}：{}", request.category(), request.symbol(),
                request.side(), request.qty(), reason);
        throw new BitgetApiException(reason);
    }

    /** 取盘口：买单吃卖档（asks），卖单吃买档（bids）。 */
    private List<List<String>> levelsFor(String category, String symbol, boolean isBuy) {
        OrderBook book = publicClient.orderBook(category, symbol, depthLimit);
        if (book == null) {
            return List.of();
        }
        List<List<String>> levels = isBuy ? book.asks() : book.bids();
        return levels == null ? List.of() : levels;
    }

    /**
     * 从规则里取基础币（两条腿的符号就是"币 + USDT"）。
     *
     * @param rule   交易对规则
     * @param symbol 交易对
     * @return 基础币，如 BTC
     */
    private static String baseCoinOf(Instrument rule, String symbol) {
        if (rule != null && rule.getBaseCoin() != null && !rule.getBaseCoin().isBlank()) {
            return rule.getBaseCoin();
        }
        return symbol.endsWith(COIN) ? symbol.substring(0, symbol.length() - COIN.length()) : symbol;
    }

    /** @return 该币的保证金折扣率；查不到按 0 处理（等价于"现货不能抵保证金"） */
    private BigDecimal discountRate(String baseCoin) {
        WatchCoin coin = watchCoinMapper.findByBaseCoin(baseCoin);
        return coin == null || coin.getDiscountRate() == null ? BigDecimal.ZERO : coin.getDiscountRate();
    }

    /**
     * 取"全部永续最新价"的快照（带 {@code simulation.ticker-cache-ms} 缓存）。
     *
     * <p><b>必须在持有 {@link #lock} 之外调用</b>：缓存过期时这里会打一次 Bitget 接口，是网络 I/O。
     * 早期版本让各个同步块<b>内部</b>去取价，一旦接口卡住（实测：对端连接半开、本地读永不返回），
     * 锁就被永久占住，管理后台和交易循环全部堵死（2026-10-06 故障）。
     *
     * <p>取价失败时沿用上一次的缓存，绝不把调用方一起拖垮——价格"旧一点"远比"整个后台卡死"好。
     *
     * @return 交易对 → 最新价（可能为空；取不到时调用方按 0 处理）
     */
    private Map<String, BigDecimal> priceSnapshot() {
        long now = System.currentTimeMillis();
        Map<String, BigDecimal> cached = tickerCache;
        if (!cached.isEmpty() && now - tickerCacheAt <= tickerCacheMs) {
            return cached;
        }
        try {
            Map<String, BigDecimal> prices = new LinkedHashMap<>();
            for (Ticker ticker : publicClient.tickers(BitgetPublicClient.USDT_FUTURES)) {
                BigDecimal price = number(ticker.lastPrice());
                if (price != null && price.signum() > 0) {
                    prices.put(ticker.symbol(), price);
                }
            }
            if (!prices.isEmpty()) {
                tickerCache = prices;
                tickerCacheAt = now;
                return prices;
            }
        } catch (Exception e) {
            log.warn("拉取最新价失败，沿用上一次缓存（{} 个币）：{}", cached.size(), e.getMessage());
        }
        return cached;
    }

    /** @return 快照里的最新价；没有该交易对时返回 0（与历史行为一致） */
    private static BigDecimal priceOf(Map<String, BigDecimal> prices, String symbol) {
        return prices.getOrDefault(symbol, BigDecimal.ZERO);
    }

    /**
     * 写一条订单记录。
     *
     * @param request      下单请求
     * @param rule         交易对规则（取基础币用）
     * @param qty          委托数量
     * @param cumExecQty   成交数量
     * @param avgPrice     成交均价
     * @param status       订单状态
     * @param rejectReason 拒绝 / 撤销原因
     * @return 落库后的订单（含自增主键，供成交明细关联）
     */
    private TradeOrder recordOrder(Request request, Instrument rule, BigDecimal qty, BigDecimal cumExecQty,
                                   BigDecimal avgPrice, String status, String rejectReason) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        TradeOrder order = new TradeOrder();
        order.setSource(SOURCE);
        order.setExchangeOrderId("mock-" + request.clientOid());
        order.setClientOid(request.clientOid());
        order.setSymbol(request.symbol());
        order.setCategory(request.category());
        order.setSide(request.side());
        order.setPosSide(request.posSide());
        order.setOrderType(request.orderType() == null ? "limit" : request.orderType());
        order.setTimeInForce(request.timeInForce());
        order.setPrice(request.price());
        order.setQty(qty);
        order.setCumExecQty(cumExecQty);
        order.setAvgPrice(avgPrice);
        order.setOrderStatus(status);
        order.setReduceOnly(0);
        order.setRejectReason(rejectReason);
        order.setExchangeCreatedTime(now);
        order.setExchangeUpdatedTime(now);
        tradeOrderMapper.insert(order);
        return order;
    }

    /** 落库模拟账户现金。 */
    private void persistAccount() {
        MockAccount row = new MockAccount();
        row.setAccountKey(ACCOUNT_KEY);
        row.setUsdtBalance(cash.setScale(STATE_SCALE, RoundingMode.HALF_UP));
        row.setInitialUsdt(initialUsdt);
        accountMapper.upsert(row);
    }

    /** 落库模拟持仓。 */
    private void persistHolding(Holding holding) {
        MockPosition row = new MockPosition();
        row.setBaseCoin(holding.baseCoin);
        row.setSpotSymbol(holding.spotSymbol);
        row.setFuturesSymbol(holding.futuresSymbol);
        row.setSpotQty(holding.spotQty.setScale(STATE_SCALE, RoundingMode.HALF_UP));
        row.setSpotAvgPrice(holding.spotAvgPrice);
        row.setPerpQty(holding.perpQty.setScale(STATE_SCALE, RoundingMode.HALF_UP));
        row.setPerpAvgPrice(holding.perpAvgPrice);
        row.setOpenedAt(holding.openedAt);
        positionMapper.upsert(row);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() || "null".equals(text) ? null : new BigDecimal(text);
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * 去掉多余尾零，供只读 DTO 使用。
     *
     * <p>{@code stripTrailingZeros()} 会把 10000 变成 1E+4（scale 为负），JSON 里就成了科学计数法；
     * 加上 {@code BigDecimal.ZERO} 把 scale 拉回 0，输出就是 10000。
     *
     * @param value 原始值
     * @return 规整后的值；入参为 null 时返回 null
     */
    private static BigDecimal trim(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().add(BigDecimal.ZERO);
    }

    /**
     * 一笔待撮合的下单请求（从交易所请求字段解析出来）。
     *
     * @param clientOid   自定义订单号
     * @param category    产品线
     * @param symbol      交易对
     * @param side        买卖方向
     * @param posSide     持仓方向
     * @param orderType   订单类型
     * @param timeInForce 有效方式
     * @param price       委托价（市价单为 null）
     * @param qty         委托数量
     */
    private record Request(String clientOid, String category, String symbol, String side, String posSide,
                           String orderType, String timeInForce, BigDecimal price, BigDecimal qty) {

        /** @return 是否买单 */
        private boolean isBuy() {
            return "buy".equalsIgnoreCase(side);
        }

        /** @return 是否 U 本位永续 */
        private boolean isFutures() {
            return BitgetPublicClient.USDT_FUTURES.equals(category);
        }

        /** @return 是否平仓方向（卖现货或买回永续空头） */
        private boolean isReduce() {
            return isFutures() ? isBuy() : !isBuy();
        }
    }

    /** 模拟持仓（内存态，落库对应 mock_position）。 */
    private static final class Holding {

        /** 标的币种。 */
        private final String baseCoin;

        /** 现货交易对。 */
        private final String spotSymbol;

        /** 永续交易对。 */
        private final String futuresSymbol;

        /** 现货多头数量。 */
        private BigDecimal spotQty = BigDecimal.ZERO;

        /** 现货多头开仓均价（平仓时用它算已实现盈亏）。 */
        private BigDecimal spotAvgPrice;

        /** 永续空头数量（正数表示空头）。 */
        private BigDecimal perpQty = BigDecimal.ZERO;

        /** 永续空头开仓均价。 */
        private BigDecimal perpAvgPrice;

        /** 开仓时间（资金费只结算它之后的结算点）。 */
        private LocalDateTime openedAt;

        private Holding(String baseCoin, String spotSymbol, String futuresSymbol) {
            this.baseCoin = baseCoin;
            this.spotSymbol = spotSymbol;
            this.futuresSymbol = futuresSymbol;
        }

        /**
         * 空头未实现盈亏。
         *
         * @param price 最新价
         * @return (开仓均价 − 最新价) × 数量；没有均价时返回 0
         */
        private BigDecimal unrealisedPnl(BigDecimal price) {
            if (perpAvgPrice == null || perpQty.signum() <= 0 || price.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return perpAvgPrice.subtract(price).multiply(perpQty);
        }

        /**
         * 现货多头未实现盈亏。
         *
         * @param price 最新价
         * @return (最新价 − 开仓均价) × 数量；没有均价时返回 0
         */
        private BigDecimal spotUnrealisedPnl(BigDecimal price) {
            if (spotAvgPrice == null || spotQty.signum() <= 0 || price.signum() <= 0) {
                return BigDecimal.ZERO;
            }
            return price.subtract(spotAvgPrice).multiply(spotQty);
        }
    }

    /**
     * 模拟账户状态（后台只读展示）。
     *
     * @param cash        现金余额（USDT）
     * @param initialUsdt 初始资金（USDT）
     * @param holdings    持仓明细
     */
    public record AccountState(BigDecimal cash, BigDecimal initialUsdt, List<HoldingView> holdings) {
    }

    /**
     * 单个币的模拟持仓视图。
     *
     * @param baseCoin      币种
     * @param spotQty       现货数量
     * @param perpQty       永续空头数量
     * @param perpAvgPrice  空头开仓均价
     * @param lastPrice     最新价
     * @param unrealisedPnl 未实现盈亏
     * @param openedAt      开仓时间
     */
    public record HoldingView(String baseCoin, BigDecimal spotQty, BigDecimal spotAvgPrice,
                              BigDecimal perpQty, BigDecimal perpAvgPrice, BigDecimal lastPrice,
                              BigDecimal unrealisedPnl, BigDecimal spotUnrealisedPnl,
                              LocalDateTime openedAt) {
    }
}
