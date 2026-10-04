package com.quantification.service;

import com.quantification.bitget.BitgetPrivateClient.AccountAssets;
import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.Ticker;
import com.quantification.entity.AccountBalanceSnapshot;
import com.quantification.entity.EventLog;
import com.quantification.entity.FundingRateHistory;
import com.quantification.entity.WatchCoin;
import com.quantification.exchange.ExchangeGateway;
import com.quantification.exchange.MockExchangeGateway;
import com.quantification.exchange.MockExchangeGateway.HoldingView;
import com.quantification.mapper.FundingIncomeMapper;
import com.quantification.mapper.FundingRateHistoryMapper;
import com.quantification.mapper.TradeFillMapper;
import com.quantification.mapper.WatchCoinMapper;
import com.quantification.service.FundingYieldService.FundingYield;
import com.quantification.service.SimulationService.SimulationStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 管理后台的数据装配层（模块 10）。
 *
 * <p>把分散在各张表 / 各服务里的数据拼成前台要的 DTO：总览、持仓、候选池、参数、
 * 事件、费率曲线。全部只读，不做任何交易操作。
 */
@Service
public class DashboardService {

    /** mock 数据的 source 标记。 */
    private static final String SOURCE = MockExchangeGateway.SOURCE;

    /** 24h 成交额缓存的 TTL（毫秒）：成交额是慢变量，缓存 1 分钟避免轮询打爆接口。 */
    private static final long TURNOVER_TTL_MS = 60_000L;

    private final ExchangeGateway exchangeGateway;
    private final SimulationService simulationService;
    private final RiskService riskService;
    private final WatchCoinMapper watchCoinMapper;
    private final FundingYieldService fundingYieldService;
    private final FundingRateHistoryMapper historyMapper;
    private final FundingIncomeMapper fundingIncomeMapper;
    private final TradeFillMapper tradeFillMapper;
    private final EventLogService eventLogService;
    private final BitgetPublicClient publicClient;
    private final Environment environment;

    /** 24h 成交额缓存。 */
    private Map<String, BigDecimal> turnoverCache = Map.of();
    private long turnoverCacheAt = 0L;

    public DashboardService(ExchangeGateway exchangeGateway,
                            SimulationService simulationService,
                            RiskService riskService,
                            WatchCoinMapper watchCoinMapper,
                            FundingYieldService fundingYieldService,
                            FundingRateHistoryMapper historyMapper,
                            FundingIncomeMapper fundingIncomeMapper,
                            TradeFillMapper tradeFillMapper,
                            EventLogService eventLogService,
                            BitgetPublicClient publicClient,
                            Environment environment) {
        this.exchangeGateway = exchangeGateway;
        this.simulationService = simulationService;
        this.riskService = riskService;
        this.watchCoinMapper = watchCoinMapper;
        this.fundingYieldService = fundingYieldService;
        this.historyMapper = historyMapper;
        this.fundingIncomeMapper = fundingIncomeMapper;
        this.tradeFillMapper = tradeFillMapper;
        this.eventLogService = eventLogService;
        this.publicClient = publicClient;
        this.environment = environment;
    }

    /**
     * 总览：账户权益 / 保证金率 / 持仓数 / 资金费 / 手续费 / 年化 / 策略状态。
     *
     * @return 总览数据
     */
    public Overview overview() {
        SimulationStatus status = simulationService.status();
        AccountAssets assets = exchangeGateway.assets();
        List<AccountBalanceSnapshot> curve = simulationService.equityCurve(1);
        LocalDateTime lastSnapshot = curve.isEmpty() ? null : curve.get(0).getSourceTime();
        return new Overview(status.mode(), status.mockEnabled(), riskService.isHalted(),
                riskService.getHaltReason(), status.accountEquity(), status.effEquity(),
                decimal(assets.mgnRatio()), decimal(assets.positionValue()),
                status.fundingIncome(), status.feeCost(), status.orderCount(), status.fillCount(),
                status.holdings().size(), status.cumulativeReturn(), status.rollingAnnualized(),
                lastSnapshot, status.note());
    }

    /**
     * 持仓：每个持仓币的两条腿、现价、未实现盈亏、累计资金费、累计手续费。
     *
     * @return 持仓列表（按币种）
     */
    public List<PositionView> positions() {
        List<HoldingView> holdings = simulationService.status().holdings();
        Map<String, BigDecimal> funding = toSymbolMap(fundingIncomeMapper.sumBySymbol(SOURCE));
        Map<String, BigDecimal> fee = toSymbolMap(tradeFillMapper.sumFeeBySymbol(SOURCE));

        List<PositionView> result = new ArrayList<>();
        for (HoldingView h : holdings) {
            result.add(new PositionView(h.baseCoin(), h.spotQty(), h.spotAvgPrice(),
                    h.perpQty(), h.perpAvgPrice(), h.lastPrice(), h.unrealisedPnl(),
                    h.spotUnrealisedPnl(), funding.getOrDefault(h.baseCoin() + "USDT", BigDecimal.ZERO),
                    fee.getOrDefault(h.baseCoin() + "USDT", BigDecimal.ZERO), h.openedAt()));
        }
        return result;
    }

    /**
     * 盈亏核算：把权益变动拆成手续费 / 资金费 / 现货与永续的已实现、未实现盈亏，
     * 并做一次恒等式校验（残差应接近 0）。
     *
     * @return 盈亏分解
     */
    public Pnl pnl() {
        SimulationStatus status = simulationService.status();
        BigDecimal equity = status.accountEquity() == null ? BigDecimal.ZERO : status.accountEquity();
        BigDecimal initial = status.initialUsdt() == null ? BigDecimal.ZERO : status.initialUsdt();
        BigDecimal fee = trim(tradeFillMapper.sumFee(SOURCE));
        BigDecimal funding = trim(fundingIncomeMapper.sumAmount(SOURCE));
        BigDecimal perpRealized = trim(tradeFillMapper.sumExecPnl(SOURCE, "perp"));
        BigDecimal spotRealized = trim(tradeFillMapper.sumExecPnl(SOURCE, "spot"));

        BigDecimal unrealized = BigDecimal.ZERO;
        for (HoldingView h : status.holdings()) {
            unrealized = unrealized.add(h.unrealisedPnl()).add(h.spotUnrealisedPnl());
        }

        // 恒等式：equity - initial = funding - fee + spotRealized + perpRealized + unrealized
        BigDecimal explained = funding.subtract(fee).add(spotRealized).add(perpRealized).add(unrealized);
        BigDecimal residual = equity.subtract(initial).subtract(explained);
        return new Pnl(initial, equity, fee, funding, spotRealized, perpRealized,
                unrealized, explained, residual);
    }

    /**
     * 候选池：篮子里每个币的净/毛年化、结算周期、折扣率、24h 成交额。
     *
     * @return 候选列表（按净年化降序）
     */
    public List<CandidateView> candidates() {
        Map<String, WatchCoin> basket = new HashMap<>();
        for (WatchCoin coin : watchCoinMapper.findEnabled()) {
            basket.put(coin.getFuturesSymbol(), coin);
        }
        Map<String, BigDecimal> turnover = turnover();
        List<CandidateView> result = new ArrayList<>();
        for (FundingYield yield : fundingYieldService.calculate()) {
            WatchCoin coin = basket.get(yield.symbol());
            if (coin == null) {
                continue;   // 只展示篮子里的币
            }
            result.add(new CandidateView(yield.symbol(), coin.getBaseCoin(), yield.intervalHours(),
                    yield.sampleCount(), yield.grossAnnualizedPct(), yield.feeDragAnnualizedPct(),
                    yield.netAnnualizedPct(), turnover.get(yield.symbol()), coin.getDiscountRate()));
        }
        result.sort((a, b) -> b.netAnnualizedPct().compareTo(a.netAnnualizedPct()));
        return result;
    }

    /**
     * 当前生效的参数（只读展示）。
     *
     * @return 配置键 → 值
     */
    public Map<String, Object> params() {
        String[] keys = {
                "strategy.enabled", "strategy.allow-real-trading", "strategy.entry-net",
                "strategy.switch-gap", "strategy.max-holdings", "strategy.max-single-weight",
                "strategy.lookback-days", "strategy.recovery-filter", "strategy.target-invest-ratio",
                "execution.price-buffer", "execution.leg-tolerance", "execution.order-timeout-ms",
                "execution.max-retries", "risk.max-drawdown", "risk.mgn-ratio-warn", "risk.mgn-ratio-reduce",
                "watch-coin.min-turnover", "watch-coin.min-discount-rate",
                "simulation.enabled", "simulation.initial-usdt", "simulation.maintenance-margin-rate",
                "collector.history-interval-ms", "collector.current-interval-ms",
                "funding-yield.lookback-days", "funding-yield.rotation-days",
                "funding-yield.spot-taker-fee-rate", "funding-yield.perp-taker-fee-rate",
                "alert.mail.enabled", "bitget.paptrading"
        };
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, environment.getProperty(key));
        }
        return result;
    }

    /** @return 最近的事件日志（新的在前） */
    public List<EventLog> events(int limit) {
        return eventLogService.recent(limit);
    }

    /** @return 某个币最近若干笔历史资金费率（按时间倒序，前端画曲线时再反序） */
    public List<FundingRateHistory> fundingRateHistory(String symbol, int limit) {
        return historyMapper.findRecent(symbol, limit);
    }

    /** 把 {@code SELECT symbol, total} 的结果转成 symbol → total 的 Map。 */
    private static Map<String, BigDecimal> toSymbolMap(List<Map<String, Object>> rows) {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Map<String, Object> row : rows) {
            Object symbol = row.get("symbol");
            Object total = row.get("total");
            if (symbol == null || total == null) {
                continue;
            }
            map.put(String.valueOf(symbol), new BigDecimal(String.valueOf(total)));
        }
        return map;
    }

    /** @return 各永续交易对的 24h 成交额（带 1 分钟缓存） */
    private Map<String, BigDecimal> turnover() {
        long now = System.currentTimeMillis();
        if (now - turnoverCacheAt <= TURNOVER_TTL_MS && !turnoverCache.isEmpty()) {
            return turnoverCache;
        }
        Map<String, BigDecimal> map = new HashMap<>();
        try {
            for (Ticker ticker : publicClient.tickers(BitgetPublicClient.USDT_FUTURES)) {
                if (ticker.turnover24h() != null && !ticker.turnover24h().isBlank()) {
                    map.put(ticker.symbol(), new BigDecimal(ticker.turnover24h()));
                }
            }
        } catch (Exception ignored) {
            // 取不到成交额就不展示该列，不影响其它数据
        }
        turnoverCache = map;
        turnoverCacheAt = now;
        return map;
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /** 去掉多余尾零，让接口返回的数字好读（0 而不是 0E-18）。 */
    private static BigDecimal trim(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.stripTrailingZeros().add(BigDecimal.ZERO);
    }

    /**
     * 总览数据。
     *
     * @param mode              交易模式（实盘 / 官方模拟盘 / 自建 mock）
     * @param mockEnabled       自建 mock 是否启用
     * @param halted            策略是否熔断
     * @param haltReason        熔断原因（未熔断为空）
     * @param accountEquity     账户总权益（USDT）
     * @param effEquity         有效权益（可作保证金的净值）
     * @param mgnRatio          维持保证金率
     * @param positionValue     仓位名义价值
     * @param fundingIncome     累计资金费收入
     * @param feeCost           累计手续费支出
     * @param orderCount        订单数
     * @param fillCount         成交笔数
     * @param holdingsCount     持仓币种数
     * @param cumulativeReturn  累计收益率（小数）
     * @param rollingAnnualized 滚动年化（小数，数据不足时为 null）
     * @param lastSnapshotTime  最近一次净值快照时间
     * @param note              说明
     */
    public record Overview(String mode, boolean mockEnabled, boolean halted, String haltReason,
                           BigDecimal accountEquity, BigDecimal effEquity, BigDecimal mgnRatio,
                           BigDecimal positionValue, BigDecimal fundingIncome, BigDecimal feeCost,
                           int orderCount, int fillCount, int holdingsCount, BigDecimal cumulativeReturn,
                           BigDecimal rollingAnnualized, LocalDateTime lastSnapshotTime, String note) {
    }

    /**
     * 单个持仓视图。
     *
     * @param baseCoin      币种
     * @param spotQty       现货数量
     * @param perpQty       永续空头数量
     * @param perpAvgPrice  空头开仓均价
     * @param lastPrice     最新价
     * @param unrealisedPnl 未实现盈亏
     * @param fundingIncome 累计资金费收入
     * @param feeCost       累计手续费
     * @param openedAt      开仓时间
     */
    public record PositionView(String baseCoin, BigDecimal spotQty, BigDecimal spotAvgPrice,
                               BigDecimal perpQty, BigDecimal perpAvgPrice,
                               BigDecimal lastPrice, BigDecimal unrealisedPnl, BigDecimal spotUnrealisedPnl,
                               BigDecimal fundingIncome, BigDecimal feeCost, LocalDateTime openedAt) {
    }

    /**
     * 盈亏分解（模块 8 的最小口径）。
     *
     * @param initialUsdt  初始资金（USDT）
     * @param equity       当前总权益（USDT）
     * @param feeCost      累计手续费（正数=支出）
     * @param fundingIncome 累计资金费（正数=收入）
     * @param spotRealized 现货腿已实现盈亏
     * @param perpRealized 永续腿已实现盈亏
     * @param unrealized   当前持仓未实现盈亏（现货 + 永续）
     * @param explained    已解释的权益变动 = 资金费 − 手续费 + 已实现 + 未实现
     * @param residual     残差 = 实际权益变动 − 已解释（应接近 0，舍入误差）
     */
    public record Pnl(BigDecimal initialUsdt, BigDecimal equity, BigDecimal feeCost,
                      BigDecimal fundingIncome, BigDecimal spotRealized, BigDecimal perpRealized,
                      BigDecimal unrealized, BigDecimal explained, BigDecimal residual) {
    }

    /**
     * 单个候选视图。
     *
     * @param symbol              交易对
     * @param baseCoin            币种
     * @param intervalHours       结算周期（小时）
     * @param sampleCount         窗口内结算次数
     * @param grossAnnualizedPct  毛年化（%）
     * @param feeDragPct          换仓成本年化（%）
     * @param netAnnualizedPct    净年化（%）
     * @param turnover24h         24h 成交额（USDT，取不到为 null）
     * @param discountRate        保证金折扣率
     */
    public record CandidateView(String symbol, String baseCoin, int intervalHours, int sampleCount,
                                BigDecimal grossAnnualizedPct, BigDecimal feeDragPct,
                                BigDecimal netAnnualizedPct, BigDecimal turnover24h,
                                BigDecimal discountRate) {
    }
}
