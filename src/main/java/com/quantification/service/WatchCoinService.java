package com.quantification.service;

import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.InstrumentInfo;
import com.quantification.bitget.BitgetPublicClient.Ticker;
import com.quantification.entity.WatchCoin;
import com.quantification.mapper.WatchCoinMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
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

    private final BitgetPublicClient bitget;
    private final WatchCoinMapper watchCoinMapper;

    /** 目标币种池的 24 小时成交额下限（USDT）。 */
    private final BigDecimal minTurnover;

    public WatchCoinService(BitgetPublicClient bitget,
                            WatchCoinMapper watchCoinMapper,
                            @Value("${watch-coin.min-turnover:5000000}") BigDecimal minTurnover) {
        this.bitget = bitget;
        this.watchCoinMapper = watchCoinMapper;
        this.minTurnover = minTurnover;
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
        Set<String> spotSymbols = usdtSymbols(BitgetPublicClient.SPOT);
        Set<String> futuresSymbols = usdtSymbols(BitgetPublicClient.USDT_FUTURES);
        Map<String, BigDecimal> turnover = turnoverBySymbol(BitgetPublicClient.USDT_FUTURES);

        // 先全部停用，再把达标的重新启用：不达标的老币会被自动淘汰
        watchCoinMapper.disableAll();

        int enabled = 0;
        int skippedByTurnover = 0;
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
            watchCoinMapper.upsert(toWatchCoin(baseCoin, symbol));
            enabled++;
        }

        SyncResult result = new SyncResult(spotSymbols.size(), futuresSymbols.size(),
                enabled, skippedByTurnover, minTurnover);
        log.info("监控篮子同步完成：{}", result);
        return result;
    }

    private WatchCoin toWatchCoin(String baseCoin, String symbol) {
        WatchCoin row = new WatchCoin();
        row.setBaseCoin(baseCoin);
        row.setSpotSymbol(symbol);
        row.setFuturesSymbol(symbol);
        row.setFuturesCategory(BitgetPublicClient.USDT_FUTURES);
        row.setNote("自动同步");
        return row;
    }

    /** 取某个产品线下所有 USDT 计价的交易对，排除 RWA 与 Reality 股票代币。 */
    private Set<String> usdtSymbols(String category) {
        Set<String> symbols = new LinkedHashSet<>();
        for (InstrumentInfo info : bitget.instruments(category)) {
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
     * @param minTurnover       本次使用的成交额门槛
     */
    public record SyncResult(int spotSymbols, int futuresSymbols, int enabled,
                             int skippedByTurnover, BigDecimal minTurnover) {
    }
}
