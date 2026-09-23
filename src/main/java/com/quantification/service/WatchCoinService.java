package com.quantification.service;

import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.InstrumentInfo;
import com.quantification.bitget.BitgetPublicClient.Ticker;
import com.quantification.entity.WatchCoin;
import com.quantification.mapper.WatchCoinMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 监控篮子同步服务。
 *
 * <p>职责：把 Bitget 上满足以下条件的币种纳入监控篮子，写进 {@code watch_coin} 表：
 * <ol>
 *   <li>现货和 U 本位永续<b>都有</b>（否则做不了"现货多 + 永续空"的对冲）；</li>
 *   <li>24 小时成交额 ≥ 配置的下限（{@code watch-coin.min-turnover}，默认 500 万 USDT）。</li>
 * </ol>
 *
 * <p>同步是"重建式"的：先把全部币种置为停用，再把符合条件的重新启用。
 * 这样成交额掉出门槛的币会自动退出篮子，不会残留下来继续被采集。
 *
 * <p>过滤规则另有两条：只保留 USDT 计价；排除 RWA 与 Reality 股票代币
 * （代币化股票，资金费逻辑不适用）。
 *
 * <p>启动 3 秒后自动同步一次，之后每天一次（见 application.yml 的 watch-coin 段）。
 * 同步必须早于资金费率采集，否则第一次采集用的是旧篮子。
 */
@Service
public class WatchCoinService {

    private static final Logger log = LoggerFactory.getLogger(WatchCoinService.class);

    /** USDT 计价后缀，用来把交易对切成基础币。 */
    private static final String QUOTE_SUFFIX = "USDT";

    /**
     * 模拟盘现货报价与真实市场允许的最大偏离。
     *
     * <p>实测（2026-09-23）：模拟盘 ETH 卖一 54500 而真实市价 2663、SOL 偏离 55%，
     * 这类币在模拟盘**永远成交不了**（可下单价位与盘口卖单完全脱节）。
     * 所以同步时做一次校验，偏离过大的币直接标记为"模拟盘不可交易"。
     */
    private static final BigDecimal MAX_DEMO_QUOTE_DEVIATION = new BigDecimal("0.05");

    private final BitgetPublicClient bitget;
    private final WatchCoinMapper watchCoinMapper;

    /** 目标币种池的 24 小时成交额下限（USDT）。 */
    private final BigDecimal minTurnover;

    /** 目标币种池的保证金折扣率下限（低于此值强平风险过高）。 */
    private final BigDecimal minDiscountRate;

    public WatchCoinService(BitgetPublicClient bitget,
                            WatchCoinMapper watchCoinMapper,
                            @Value("${watch-coin.min-turnover:5000000}") BigDecimal minTurnover,
                            @Value("${watch-coin.min-discount-rate:0.8}") BigDecimal minDiscountRate) {
        this.bitget = bitget;
        this.watchCoinMapper = watchCoinMapper;
        this.minTurnover = minTurnover;
        this.minDiscountRate = minDiscountRate;
    }

    /** 定时同步篮子（默认启动 3 秒后一次，之后每天一次，见 application.yml）。 */
    @Scheduled(initialDelayString = "${watch-coin.sync-initial-delay-ms:3000}",
               fixedDelayString = "${watch-coin.sync-interval-ms:86400000}")
    public void scheduledSync() {
        syncEligibleCoins();
    }

    /**
     * 同步一次篮子。
     *
     * @return 同步结果（现货交易对数 / 永续交易对数 / 入池币种数 / 因成交额被排除的币种数）
     */
    @Transactional
    public SyncResult syncEligibleCoins() {
        Set<String> spotSymbols = usdtSymbols(BitgetPublicClient.SPOT, false);
        Set<String> futuresSymbols = usdtSymbols(BitgetPublicClient.USDT_FUTURES, false);
        // 模拟盘支持范围不同，单独拉一份，记到 demo_supported 字段（不需要额外配置）
        Set<String> demoSpot = usdtSymbols(BitgetPublicClient.SPOT, true);
        Set<String> demoPerp = usdtSymbols(BitgetPublicClient.USDT_FUTURES, true);
        Map<String, BigDecimal> turnover = turnoverBySymbol(BitgetPublicClient.USDT_FUTURES);
        Map<String, BigDecimal> discountRates = discountRateByCoin();

        // 先全部停用，再把达标的重新启用：不达标的老币会被自动淘汰
        watchCoinMapper.disableAll();

        int enabled = 0;
        int skippedByTurnover = 0;
        int skippedByDiscount = 0;
        for (String symbol : futuresSymbols) {
            if (!spotSymbols.contains(symbol)) {
                continue;
            }
            if (turnover.getOrDefault(symbol, BigDecimal.ZERO).compareTo(minTurnover) < 0) {
                skippedByTurnover++;
                continue;
            }
            String baseCoin = symbol.substring(0, symbol.length() - QUOTE_SUFFIX.length());
            if (baseCoin.isEmpty()) {
                continue;
            }
            // 折扣率太低意味着现货几乎不能抵保证金，强平风险过高，直接排除
            BigDecimal discountRate = discountRates.get(baseCoin);
            if (discountRate == null || discountRate.compareTo(minDiscountRate) < 0) {
                skippedByDiscount++;
                continue;
            }
            boolean demoTradable = demoSpot.contains(symbol) && demoPerp.contains(symbol);
            if (demoTradable && !demoQuoteSane(symbol)) {
                log.warn("{} 的模拟盘现货报价异常（与真实市场偏离超过 {}），标记为模拟盘不可交易",
                        symbol, MAX_DEMO_QUOTE_DEVIATION);
                demoTradable = false;
            }
            watchCoinMapper.upsert(toWatchCoin(baseCoin, symbol, discountRate, demoTradable));
            enabled++;
        }

        SyncResult result = new SyncResult(spotSymbols.size(), futuresSymbols.size(),
                enabled, skippedByTurnover, skippedByDiscount, minTurnover, minDiscountRate);
        log.info("监控篮子同步完成：{}", result);
        return result;
    }

    private WatchCoin toWatchCoin(String baseCoin, String symbol, BigDecimal discountRate, boolean demoTradable) {
        WatchCoin row = new WatchCoin();
        row.setBaseCoin(baseCoin);
        row.setSpotSymbol(symbol);
        row.setFuturesSymbol(symbol);
        row.setFuturesCategory(BitgetPublicClient.USDT_FUTURES);
        row.setDiscountRate(discountRate);
        row.setRealSupported(1);
        row.setDemoSupported(demoTradable ? 1 : 0);
        row.setNote("自动同步");
        return row;
    }

    /** 取各币种的保证金折扣率（用第一档，即小额持仓那一档）。 */
    private Map<String, BigDecimal> discountRateByCoin() {
        Map<String, BigDecimal> map = new HashMap<>();
        for (BitgetPublicClient.DiscountRate rate : bitget.discountRates()) {
            if (rate.list() == null || rate.list().isEmpty()) {
                continue;
            }
            String value = rate.list().get(0).discountRate();
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                map.put(rate.coin(), new BigDecimal(value));
            } catch (NumberFormatException ignored) {
                // 交易所偶尔返回非数字，忽略该条
            }
        }
        return map;
    }

    /**
     * 校验模拟盘的现货报价是否可信。
     *
     * <p>做法：把模拟盘的最优卖价与真实市场的最优卖价对比，偏离超过阈值即视为不可信。
     * 取不到数据时不拦（宁可放行，也不因为接口抖动把正常币误杀）。
     *
     * @param symbol 交易对
     * @return true = 报价可信（模拟盘可交易）
     */
    private boolean demoQuoteSane(String symbol) {
        try {
            BigDecimal real = bestAsk(symbol, false);
            BigDecimal demo = bestAsk(symbol, true);
            if (real == null || demo == null || real.signum() <= 0) {
                return true;
            }
            BigDecimal deviation = demo.subtract(real).abs()
                    .divide(real, 6, java.math.RoundingMode.HALF_UP);
            return deviation.compareTo(MAX_DEMO_QUOTE_DEVIATION) <= 0;
        } catch (Exception e) {
            log.warn("校验模拟盘报价失败 {}：{}", symbol, e.getMessage());
            return true;
        }
    }

    /** @return 现货最优卖价（demo=true 取模拟盘） */
    private BigDecimal bestAsk(String symbol, boolean demo) {
        BitgetPublicClient.OrderBook book = demo
                ? bitget.demoOrderBook(BitgetPublicClient.SPOT, symbol, 5)
                : bitget.orderBook(BitgetPublicClient.SPOT, symbol, 5);
        if (book.asks() == null || book.asks().isEmpty()) {
            return null;
        }
        return new BigDecimal(book.asks().get(0).get(0));
    }

    /** 取某个产品线下所有 USDT 计价的交易对，排除 RWA 与 Reality 股票代币。 */
    private Set<String> usdtSymbols(String category, boolean demo) {
        Set<String> symbols = new LinkedHashSet<>();
        List<InstrumentInfo> infos = demo ? bitget.demoInstruments(category) : bitget.instruments(category);
        for (InstrumentInfo info : infos) {
            if (info.symbol() == null || !info.symbol().endsWith(QUOTE_SUFFIX)) {
                continue;
            }
            if (isYes(info.isRwa()) || isYes(info.isReality())) {
                continue;
            }
            symbols.add(info.symbol());
        }
        return symbols;
    }

    /** 取永续合约各交易对的 24 小时成交额。 */
    private Map<String, BigDecimal> turnoverBySymbol(String category) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Ticker ticker : bitget.tickers(category)) {
            String symbol = ticker.symbol();
            String turnover24h = ticker.turnover24h();
            if (symbol == null || turnover24h == null || turnover24h.isBlank()) {
                continue;
            }
            try {
                map.put(symbol, new BigDecimal(turnover24h));
            } catch (NumberFormatException ignored) {
                // 交易所偶尔会返回非数字，忽略该条即可
            }
        }
        return map;
    }

    private static boolean isYes(String value) {
        return "yes".equalsIgnoreCase(value);
    }

    /**
     * 同步结果。
     *
     * @param spotSymbols       交易所上 USDT 计价的现货交易对数
     * @param futuresSymbols    交易所上 USDT 计价的永续交易对数
     * @param enabled           本次纳入篮子的币种数（启用状态）
     * @param skippedByTurnover 因 24h 成交额不达标被排除的币种数
     * @param skippedByDiscount 因保证金折扣率不达标被排除的币种数
     * @param minTurnover       本次使用的成交额门槛
     * @param minDiscountRate   本次使用的折扣率门槛
     */
    public record SyncResult(int spotSymbols, int futuresSymbols, int enabled,
                             int skippedByTurnover, int skippedByDiscount,
                             BigDecimal minTurnover, BigDecimal minDiscountRate) {
    }
}
