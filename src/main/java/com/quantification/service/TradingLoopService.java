package com.quantification.service;

import com.quantification.bitget.BitgetPrivateClient.AccountAssets;
import com.quantification.bitget.BitgetPrivateClient.AssetCoin;
import com.quantification.bitget.BitgetPrivateClient.Position;
import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.Ticker;
import com.quantification.entity.WatchCoin;
import com.quantification.exchange.ExchangeGateway;
import com.quantification.exchange.TradeMode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

/**
 * 交易循环（模块 3~6 的编排）：定时评估 → 决策 → 执行，并把每一步写进日志。
 *
 * <p><b>并发控制</b>：单机运行，用内存的 {@link ReentrantLock} 保证同一时刻只有一次评估在执行；
 * 采集类任务（篮子同步、费率采集）是独立线程，与本循环并行、互不阻塞。
 *
 * <p><b>安全闸</b>：
 * <ul>
 *   <li>{@code strategy.enabled=false} → 只评估、不下单；</li>
 *   <li>实盘时还需要 {@code strategy.allow-real-trading=true} 才真下单，
 *       否则只打印"本应下单"的日志。这条专门防"以为在模拟盘、结果下了真单"。</li>
 * </ul>
 * 模式的判定交给 {@link ExchangeGateway}：官方模拟盘与自建 mock 都不会动用真实资金，
 * 因此不需要第二道开关；只有 {@code REAL} 模式才要求 {@code allow-real-trading=true}。
 *
 * <p><b>日志约定</b>：每次评估都输出"看到什么 → 决定什么 → 为什么"，即使决定是"什么都不做"。
 */
@Service
public class TradingLoopService {

    private static final Logger log = LoggerFactory.getLogger(TradingLoopService.class);

    /** 交易网关：实盘 / 官方模拟盘 / 自建 mock。 */
    private final ExchangeGateway exchange;
    private final BitgetPublicClient publicClient;
    private final FundingAnalysisService analysisService;
    private final StrategyService strategyService;
    private final OrderExecutionService executionService;
    private final RiskService riskService;
    private final MailAlertService mailAlert;
    private final EventLogService eventLog;

    private final boolean enabled;
    private final boolean allowRealTrading;
    private final BigDecimal targetInvestRatio;
    private final BigDecimal legTolerance;
    /** 连续多少次瞬时网络失败后发一封提醒（不熔断）。 */
    private final int transientWarnThreshold;

    /** 连续瞬时网络失败的计数（成功评估一次就清零）。 */
    private final AtomicInteger transientFailures = new AtomicInteger();

    /** 保证同一时刻只有一次评估在跑。 */
    private final ReentrantLock loopLock = new ReentrantLock();

    /** 启动后是否已做过一次对账。 */
    private volatile boolean reconciled = false;

    public TradingLoopService(ExchangeGateway exchange,
                              BitgetPublicClient publicClient,
                              FundingAnalysisService analysisService,
                              StrategyService strategyService,
                              OrderExecutionService executionService,
                              RiskService riskService,
                              MailAlertService mailAlert,
                              EventLogService eventLog,
                              @Value("${strategy.enabled:true}") boolean enabled,
                              @Value("${strategy.allow-real-trading:false}") boolean allowRealTrading,
                              @Value("${strategy.target-invest-ratio:0.95}") BigDecimal targetInvestRatio,
                              @Value("${execution.leg-tolerance:0.005}") BigDecimal legTolerance,
                              @Value("${alert.transient-failures-before-warning:6}") int transientWarnThreshold) {
        this.exchange = exchange;
        this.publicClient = publicClient;
        this.analysisService = analysisService;
        this.strategyService = strategyService;
        this.executionService = executionService;
        this.riskService = riskService;
        this.mailAlert = mailAlert;
        this.eventLog = eventLog;
        this.enabled = enabled;
        this.allowRealTrading = allowRealTrading;
        this.targetInvestRatio = targetInvestRatio;
        this.legTolerance = legTolerance;
        this.transientWarnThreshold = transientWarnThreshold;
    }

    /** 定时评估（默认启动 20 秒后第一次，之后每小时一次）。 */
    @Scheduled(initialDelayString = "${strategy.initial-delay-ms:20000}",
               fixedDelayString = "${strategy.evaluate-interval-ms:3600000}")
    public void scheduledEvaluate() {
        if (!loopLock.tryLock()) {
            log.warn("上一次评估还没结束，本次跳过");
            return;
        }
        try {
            evaluate();
            transientFailures.set(0);
        } catch (Exception e) {
            // 分级：瞬时网络错误不熔断（下个周期自动重试），只有业务/未知错误才熔断。
            // 否则网络抖一下策略就停到下次重启，无人值守时等于静默失效。
            if (isTransient(e)) {
                int failures = transientFailures.incrementAndGet();
                log.warn("评估遇到瞬时网络错误（连续 {} 次），本次跳过，下个周期自动重试：{}",
                        failures, e.getMessage());
                if (failures == transientWarnThreshold) {
                    mailAlert.send("网络异常（未熔断）",
                            "连续 " + failures + " 次评估因网络错误失败，策略仍在自动重试、未熔断，"
                                    + "但请留意网络是否长时间不可用。最近一次错误：" + e.getMessage());
                }
            } else {
                log.error("评估过程异常，触发熔断", e);
                riskService.halt("评估异常: " + e.getMessage());
            }
        } finally {
            loopLock.unlock();
        }
    }

    /**
     * 判断异常是否属于"瞬时网络错误"（这类错误下个周期重试即可，不该熔断）。
     *
     * <p>会沿着 cause 链往下找：RestClient 的网络错误包装成 {@link ResourceAccessException}，
     * 底层通常是 SSL 握手失败 / 连接重置 / 连接超时 / 域名解析失败。
     *
     * @param throwable 异常
     * @return true = 瞬时网络错误；false = 业务错误或未知异常（应熔断）
     */
    static boolean isTransient(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ResourceAccessException
                    || current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketException
                    || current instanceof javax.net.ssl.SSLException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 一次完整评估。 */
    public void evaluate() {
        if (!exchange.isConfigured()) {
            log.warn("当前模式（{}）还没有下单能力（未配置 API Key），本次只做本地分析",
                    exchange.mode().getLabel());
        }

        // ---------- 1. 读交易所真实状态（交易所永远是真相）----------
        AccountAssets assets = exchange.isConfigured() ? exchange.assets() : null;
        List<Position> positions = exchange.isConfigured()
                ? exchange.positions(BitgetPublicClient.USDT_FUTURES, null) : List.of();
        Map<String, BigDecimal> perpHoldings = new LinkedHashMap<>();
        for (Position position : positions) {
            if (position.total() != null && new BigDecimal(position.total()).signum() > 0) {
                perpHoldings.put(position.symbol(), new BigDecimal(position.total()));
            }
        }
        Map<String, BigDecimal> spotHoldings = new LinkedHashMap<>();
        BigDecimal equity = BigDecimal.ZERO;
        if (assets != null) {
            equity = decimal(assets.accountEquity());
            for (AssetCoin coin : assets.assets() == null ? List.<AssetCoin>of() : assets.assets()) {
                BigDecimal available = decimal(coin.available());
                if (available.signum() > 0 && !"USDT".equals(coin.coin()) && !"USDC".equals(coin.coin())) {
                    spotHoldings.put(coin.coin(), available);
                }
            }
        }

        if (!reconciled) {
            reconcile(equity, spotHoldings, perpHoldings);
            reconciled = true;
        }

        // ---------- 2. 风控检查 ----------
        if (assets != null) {
            BigDecimal drawdown = riskService.updateEquityAndDrawdown(equity);
            log.info("账户状态：权益 {} USDT，有效权益 {}，维持保证金率 {}，回撤 {}",
                    equity.toPlainString(), assets.effEquity(), assets.mgnRatio(), pct(drawdown));
            if (riskService.checkMargin(decimal(assets.mgnRatio()))) {
                riskService.halt("维持保证金率进入减仓区");
            }
        }
        if (riskService.isHalted()) {
            log.error("当前处于熔断状态（{}），本次只观察、不下单", riskService.getHaltReason());
            return;
        }

        // ---------- 3. 费率分析 ----------
        List<FundingAnalysisService.Candidate> candidates = analysisService.rankCandidates();
        log.info("费率分析：窗口 {} 天，共 {} 个币有完整数据，换仓成本年化 {}",
                analysisService.getLookbackDays(), candidates.size(),
                pct(analysisService.annualRotationCost()));
        candidates.stream().limit(8).forEach(c -> log.info(
                "  候选 {} 周期 {}h 毛年化 {} 净年化 {}",
                c.symbol(), c.intervalHours(), pct(c.grossAnnualized()), pct(c.netAnnualized())));

        // ---------- 4. 决策 ----------
        Map<String, BigDecimal> turnover = turnoverBySymbol();
        // 只取当前模式（模拟盘/实盘）真正能交易的币，避免对不支持的币下单
        // 只有官方模拟盘需要按"模拟盘支持清单"过滤；实盘与自建 mock 都用真实市场
        Map<String, WatchCoin> coins = strategyService.enabledCoins(exchange.mode() == TradeMode.DEMO);
        // 先算当前持仓：恢复轮数过滤只卡"新进"，已持仓的币不重复过滤（避免反复开平）
        Map<String, BigDecimal> current = currentWeights(equity, spotHoldings, perpHoldings);
        Map<String, BigDecimal> target = strategyService.decideTarget(candidates, turnover, coins.keySet(), current.keySet());

        log.info("决策：当前持仓 {} | 目标仓位 {}", render(current), render(target));
        if (current.keySet().equals(target.keySet())) {
            log.info("结论：持仓与目标一致，本次不调仓");
            return;
        }

        // ---------- 5. 执行 ----------
        boolean canTrade = enabled && (exchange.mode().isSimulated() || allowRealTrading);
        if (!canTrade) {
            log.warn("结论：本应调仓，但下单被安全闸拦住（enabled={}，模式={}，允许实盘={}）",
                    enabled, exchange.mode().getLabel(), allowRealTrading);
            return;
        }

        // 先平掉不在目标里的仓位（降低敞口优先），再建新仓
        for (Map.Entry<String, BigDecimal> entry : current.entrySet()) {
            if (target.containsKey(entry.getKey())) {
                continue;
            }
            WatchCoin coin = coins.get(entry.getKey());
            if (coin == null) {
                // 该币已掉出监控篮子（成交额/折扣率掉出门槛）但仍持仓：照样平掉，
                // 绝不能留成孤儿仓位——否则资金被锁死，新仓永远建不起来。
                log.warn("调仓：{} 已掉出篮子但仍持仓，按平仓处理", entry.getKey());
                coin = orphanCoin(entry.getKey());
            }
            log.info("调仓：平掉 {}（不在目标仓位里）", coin.getBaseCoin());
            executionService.closePosition(coin,
                    spotHoldings.getOrDefault(coin.getBaseCoin(), BigDecimal.ZERO),
                    perpHoldings.getOrDefault(coin.getFuturesSymbol(), BigDecimal.ZERO));
        }
        for (Map.Entry<String, BigDecimal> entry : target.entrySet()) {
            if (current.containsKey(entry.getKey())) {
                continue;
            }
            WatchCoin coin = coins.get(entry.getKey());
            if (coin == null) {
                continue;
            }
            BigDecimal notional = equity.multiply(entry.getValue()).setScale(2, RoundingMode.DOWN);
            log.info("调仓：建仓 {}，目标名义 {} USDT（权重 {}）",
                    coin.getBaseCoin(), notional.toPlainString(), pct(entry.getValue()));
            var result = executionService.openPosition(coin, notional,
                    turnover.getOrDefault(coin.getFuturesSymbol(), BigDecimal.ZERO));
            if (!result.success()) {
                if (result.residualExposure()) {
                    // 真有残留敞口才熔断（这时继续跑很危险）
                    log.error("建仓 {} 失败且存在残留敞口：{}", coin.getBaseCoin(), result.message());
                    riskService.halt("建仓失败且存在残留敞口: " + result.message());
                    return;
                }
                // 已回退、无残留敞口 → 只是这个币这次做不成，跳过它，下轮评估自然重试。
                // 不要熔断：否则一个外部环境问题（比如模拟盘某币报价异常）会把整个策略锁死。
                log.warn("建仓 {} 本次失败（已回退、无残留敞口），跳过该币，下一个评估周期会自然重试：{}",
                        coin.getBaseCoin(), result.message());
                continue;
            }
        }
    }

    /**
     * 启动对账：以交易所为准，把"交易所有仓位、本地无记录"的情况记录下来并告警。
     *
     * @param equity        账户权益
     * @param spotHoldings  现货币种余额
     * @param perpHoldings  合约持仓
     */
    private void reconcile(BigDecimal equity, Map<String, BigDecimal> spotHoldings,
                           Map<String, BigDecimal> perpHoldings) {
        log.info("启动对账：账户权益 {} USDT，现货持仓 {} 种，合约持仓 {} 个",
                equity.toPlainString(), spotHoldings.size(), perpHoldings.size());
        for (Map.Entry<String, BigDecimal> entry : perpHoldings.entrySet()) {
            log.warn("对账发现交易所已有合约持仓 {} {}：按【交易所为准】原则接管，后续按目标仓位处理",
                    entry.getKey(), entry.getValue().toPlainString());
            eventLog.log("system", "WARN", "reconcile",
                    "启动对账接管已有持仓 " + entry.getKey(),
                    "数量 " + entry.getValue().toPlainString());
        }
        if (perpHoldings.isEmpty() && spotHoldings.isEmpty()) {
            log.info("启动对账：当前空仓，从零开始");
        }
    }

    /** @return 每个交易对的 24h 成交额 */
    private Map<String, BigDecimal> turnoverBySymbol() {
        Map<String, BigDecimal> map = new HashMap<>();
        for (Ticker ticker : publicClient.tickers(BitgetPublicClient.USDT_FUTURES)) {
            if (ticker.turnover24h() != null && !ticker.turnover24h().isBlank()) {
                try {
                    map.put(ticker.symbol(), new BigDecimal(ticker.turnover24h()));
                } catch (NumberFormatException ignored) {
                    // 忽略异常值
                }
            }
        }
        return map;
    }

    /**
     * 把交易所的真实持仓换算成"占账户权益的权重"。
     *
     * <p>遍历的是交易所的<b>全部</b>永续持仓，而不是只遍历篮子——这样"已掉出篮子但仍持仓"
     * 的孤儿仓位也会被看见，进而在下次调仓时被平掉。
     */
    private Map<String, BigDecimal> currentWeights(BigDecimal equity, Map<String, BigDecimal> spotHoldings,
                                                   Map<String, BigDecimal> perpHoldings) {
        Map<String, BigDecimal> weights = new LinkedHashMap<>();
        if (equity.signum() <= 0) {
            return weights;
        }
        for (Map.Entry<String, BigDecimal> entry : perpHoldings.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal perp = entry.getValue();
            if (perp.signum() <= 0) {
                continue;
            }
            BigDecimal spot = spotHoldings.getOrDefault(baseCoinOf(symbol), BigDecimal.ZERO);
            if (spot.signum() <= 0) {
                continue;
            }
            // 权重用合约腿的名义价值近似（两腿本该等值）
            BigDecimal price = lastPrice(symbol);
            if (price.signum() <= 0) {
                continue;
            }
            weights.put(symbol, perp.multiply(price).divide(equity, 8, RoundingMode.HALF_UP));
        }
        return weights;
    }

    /** USDT 计价交易对里，把 "BTCUSDT" 还原成基础币 "BTC"。 */
    private static String baseCoinOf(String symbol) {
        return symbol.endsWith("USDT") ? symbol.substring(0, symbol.length() - 4) : symbol;
    }

    /**
     * 给"已不在篮子里但仍有持仓"的币拼一个最小 {@link WatchCoin}，好让它走平仓逻辑。
     *
     * <p>现货与永续的交易对符号在 USDT 计价下相同（如 FARTCOINUSDT），
     * 所以这里两条腿都用同一个符号即可。
     */
    private static WatchCoin orphanCoin(String futuresSymbol) {
        WatchCoin coin = new WatchCoin();
        coin.setBaseCoin(baseCoinOf(futuresSymbol));
        coin.setSpotSymbol(futuresSymbol);
        coin.setFuturesSymbol(futuresSymbol);
        coin.setFuturesCategory(BitgetPublicClient.USDT_FUTURES);
        return coin;
    }

    private BigDecimal lastPrice(String symbol) {
        for (Ticker ticker : publicClient.tickers(BitgetPublicClient.USDT_FUTURES)) {
            if (symbol.equals(ticker.symbol()) && ticker.lastPrice() != null) {
                return decimal(ticker.lastPrice());
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    private static String pct(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private static String render(Map<String, BigDecimal> weights) {
        if (weights.isEmpty()) {
            return "空仓";
        }
        StringBuilder sb = new StringBuilder();
        weights.forEach((symbol, weight) -> sb.append(symbol).append('=').append(pct(weight)).append(' '));
        return sb.toString().trim();
    }
}
