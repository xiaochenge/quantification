package com.quantification.service;

import com.quantification.entity.WatchCoin;
import com.quantification.mapper.WatchCoinMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 策略与调仓（模块 4）：把"候选币排序"变成"目标仓位"。
 *
 * <p>规则（见 docs/phase-2-strategy-design.md 模块 4/5 定稿）：
 * <ol>
 *   <li>取净年化 ≥ 建仓门槛的候选，按净年化排序取前 N 个（N = max-holdings）；</li>
 *   <li>按 24h 成交额分配权重，单币权重不超过上限（超出部分不追补，直接压低总投入）；</li>
 *   <li>整体投入比例留余量（默认 95%）作为保证金缓冲。</li>
 * </ol>
 */
@Service
public class StrategyService {

    private static final Logger log = LoggerFactory.getLogger(StrategyService.class);

    private final WatchCoinMapper watchCoinMapper;
    private final FundingAnalysisService analysisService;
    private final BigDecimal entryNet;
    private final int maxHoldings;
    private final BigDecimal maxSingleWeight;
    private final BigDecimal targetInvestRatio;
    private final BigDecimal switchGap;
    private final int minHoldingDays;

    /** 时间统一用东八区（与全项目一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public StrategyService(WatchCoinMapper watchCoinMapper,
                           FundingAnalysisService analysisService,
                           @Value("${strategy.entry-net:0.05}") BigDecimal entryNet,
                           @Value("${strategy.max-holdings:3}") int maxHoldings,
                           @Value("${strategy.max-single-weight:0.40}") BigDecimal maxSingleWeight,
                           @Value("${strategy.target-invest-ratio:0.95}") BigDecimal targetInvestRatio,
                           @Value("${strategy.switch-gap:0.10}") BigDecimal switchGap,
                           @Value("${strategy.min-holding-days:7}") int minHoldingDays) {
        this.watchCoinMapper = watchCoinMapper;
        this.analysisService = analysisService;
        this.entryNet = entryNet;
        this.maxHoldings = maxHoldings;
        this.maxSingleWeight = maxSingleWeight;
        this.targetInvestRatio = targetInvestRatio;
        this.switchGap = switchGap;
        this.minHoldingDays = minHoldingDays;
    }

    /**
     * 算出目标仓位。
     *
     * @param candidates 按净年化排序的候选
     * @param turnover   各交易对的 24h 成交额（用于权重分配）
     * @param tradable   当前运行模式下**真正能交易**的交易对集合（模拟盘与实盘支持范围不同）；
     *                   不在这里过滤的话，决策日志会列出根本下不了单的币（实测踩过）
     * @param currentSymbols 当前已持有的交易对（恢复轮数过滤只卡"新进"，不卡已持仓，避免反复开平）
     * @param openedAt    各交易对的建仓时间；缺项（如实盘拿不到）表示"不限制最短持有期"
     * @return 目标仓位：交易对 → 目标权重（占总资金），空 Map 表示应空仓
     */
    public Map<String, BigDecimal> decideTarget(List<FundingAnalysisService.Candidate> candidates,
                                               Map<String, BigDecimal> turnover,
                                               Set<String> tradable,
                                               Set<String> currentSymbols,
                                               Map<String, LocalDateTime> openedAt) {
        Set<String> held = currentSymbols == null ? Set.of() : currentSymbols;

        // 第一遍：把"这一轮真正能买、且净年化达标"的候选按净年化降序挑出来。
        List<FundingAnalysisService.Candidate> eligible = new ArrayList<>();
        int skippedNotTradable = 0;
        for (FundingAnalysisService.Candidate candidate : candidates) {
            if (tradable != null && !tradable.contains(candidate.symbol())) {
                skippedNotTradable++;
                continue;   // 该币在当前模式下不可交易，直接跳过
            }
            if (candidate.netAnnualized().compareTo(entryNet) < 0) {
                break;      // 候选按净年化降序，后面只会更低，直接停
            }
            eligible.add(candidate);
        }

        // 第二遍：锁定目标名单。顺序体现三条纪律——
        //   ① 还拿得住的持仓先保住（掉出篮子 / 跌破门槛的不在此列，会被无条件平掉）；
        //   ② 有空位就补最好的新币——这是"加仓"，不是"换仓"，不需要滞回；
        //   ③ 仓位满了以后，新币必须比"手上最差的那个"高出一整个 switch-gap 才值得换，
        //      否则按兵不动（换一次要付双边手续费 + 买卖价差，频繁小换是纯亏损）。
        List<FundingAnalysisService.Candidate> selected = new ArrayList<>();
        for (FundingAnalysisService.Candidate candidate : eligible) {
            if (held.contains(candidate.symbol()) && selected.size() < maxHoldings) {
                selected.add(candidate);
            }
        }
        int keptCount = selected.size();
        int skippedByRecovery = 0;
        int blockedByGap = 0;
        int blockedByHolding = 0;
        LocalDateTime now = LocalDateTime.now(ZONE);
        for (FundingAnalysisService.Candidate candidate : eligible) {
            if (held.contains(candidate.symbol())) {
                continue;
            }
            // 恢复轮数过滤：只卡新进，不卡已持仓（避免反复开平）
            if (!analysisService.recoveryFilterPassed(candidate.symbol())) {
                skippedByRecovery++;
                continue;
            }
            if (selected.size() < maxHoldings) {
                selected.add(candidate);        // 有空位：直接补，不用等到"好很多"
                continue;
            }
            // 满仓才谈得上"换"。换的对象只能是"持有已满最短持有期"的持仓——
            // 刚换过去的币下一小时又被换回来，是纯粹的摩擦成本，必须挡住。
            FundingAnalysisService.Candidate worst =
                    replaceableWorstOf(selected, held, openedAt, now);
            if (worst == null) {
                blockedByHolding++;
                log.info("最短持有期：现有持仓都没满 {} 天，{} 再好也先不换", minHoldingDays, candidate.symbol());
                break;
            }
            BigDecimal gap = candidate.netAnnualized().subtract(worst.netAnnualized());
            if (gap.compareTo(switchGap) < 0) {
                // 候选按净年化降序，这个不够格后面的只会更小，直接收工
                blockedByGap++;
                log.info("换仓滞回：{} 净年化 {} 只比最差持仓 {} 的 {} 高 {}，不足换仓门槛 {}，本次不换",
                        candidate.symbol(), percent(candidate.netAnnualized()), worst.symbol(),
                        percent(worst.netAnnualized()), percent(gap), percent(switchGap));
                break;
            }
            log.info("换仓滞回：{} 净年化 {} 比最差持仓 {} 的 {} 高 {}，达到换仓门槛 {}，替换",
                    candidate.symbol(), percent(candidate.netAnnualized()), worst.symbol(),
                    percent(worst.netAnnualized()), percent(gap), percent(switchGap));
            selected.remove(worst);
            selected.add(candidate);
        }

        if (selected.isEmpty()) {
            log.info("目标仓位：空仓（{} 个不可交易被排除，{} 个未过恢复轮数过滤，其余未达建仓门槛 净年化 {}）",
                    skippedNotTradable, skippedByRecovery, percent(entryNet));
            return Map.of();
        }
        log.info("目标仓位：从 {} 个可交易候选中选出 {} 个（保留持仓 {} 个、新增 {} 个；"
                        + "{} 个不可交易、{} 个未过恢复轮数过滤、{} 次换仓被滞回拦下、{} 次被最短持有期拦下）",
                tradable == null ? candidates.size() : tradable.size(), selected.size(),
                keptCount, selected.size() - keptCount,
                skippedNotTradable, skippedByRecovery, blockedByGap, blockedByHolding);

        Map<String, BigDecimal> target = new LinkedHashMap<>();
        BigDecimal turnoverSum = BigDecimal.ZERO;
        for (FundingAnalysisService.Candidate candidate : selected) {
            turnoverSum = turnoverSum.add(turnover.getOrDefault(candidate.symbol(), BigDecimal.ZERO));
        }
        for (FundingAnalysisService.Candidate candidate : selected) {
            BigDecimal weight;
            if (turnoverSum.signum() > 0) {
                weight = turnover.getOrDefault(candidate.symbol(), BigDecimal.ZERO)
                        .divide(turnoverSum, 8, RoundingMode.HALF_UP);
            } else {
                weight = BigDecimal.ONE.divide(BigDecimal.valueOf(selected.size()), 8, RoundingMode.HALF_UP);
            }
            // 单币上限：超出的部分不追补，直接放弃（宁可少投，也不超限）
            if (weight.compareTo(maxSingleWeight) > 0) {
                weight = maxSingleWeight;
            }
            target.put(candidate.symbol(), weight.multiply(targetInvestRatio).setScale(8, RoundingMode.DOWN));
        }
        return target;
    }

    /**
     * 名单里"可以换掉"的最差持仓：净年化最低，且已经拿满最短持有期。
     *
     * <p>只考虑本来就是持仓的币（本轮刚加进来的新仓当然不能马上换掉）。
     * 全部都不满足最短持有期时返回 null，调用方据此"本次不换"。
     */
    private FundingAnalysisService.Candidate replaceableWorstOf(
            List<FundingAnalysisService.Candidate> selected, Set<String> held,
            Map<String, LocalDateTime> openedAt, LocalDateTime now) {
        return selected.stream()
                .filter(candidate -> held.contains(candidate.symbol()))
                .filter(candidate -> holdingPeriodElapsed(candidate.symbol(), openedAt, now))
                .min(Comparator.comparing(FundingAnalysisService.Candidate::netAnnualized))
                .orElse(null);
    }

    /** @return 该持仓是否已满最短持有期；拿不到建仓时间时不限制（避免把仓位永久锁死） */
    private boolean holdingPeriodElapsed(String symbol, Map<String, LocalDateTime> openedAt,
                                         LocalDateTime now) {
        if (minHoldingDays <= 0) {
            return true;
        }
        LocalDateTime opened = openedAt == null ? null : openedAt.get(symbol);
        if (opened == null) {
            return true;
        }
        return !opened.isAfter(now.minusDays(minHoldingDays));
    }

    /**
     * 取"当前运行模式下可交易"的币种。
     *
     * <p>模拟盘与实盘支持范围不同，标记存在 watch_coin 表里（demo_supported / real_supported），
     * 按模式过滤即可，不需要额外配置。
     *
     * @param demoInstruments true = 按官方模拟盘的支持清单过滤（模拟盘只覆盖少数币）；
     *                        false = 用真实市场支持清单（实盘与自建 mock 都用它）
     * @return 交易对 → 币种信息
     */
    public Map<String, WatchCoin> enabledCoins(boolean demoInstruments) {
        Map<String, WatchCoin> map = new LinkedHashMap<>();
        for (WatchCoin coin : watchCoinMapper.findTradable(demoInstruments)) {
            map.put(coin.getFuturesSymbol(), coin);
        }
        return map;
    }

    /** @return 建仓门槛（净年化） */
    public BigDecimal getEntryNet() {
        return entryNet;
    }

    /** 小数转百分比文本，便于日志阅读。 */
    private static String percent(BigDecimal value) {
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }
}
