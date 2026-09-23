package com.quantification.service;

import com.quantification.entity.WatchCoin;
import com.quantification.mapper.WatchCoinMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
    private final BigDecimal entryNet;
    private final int maxHoldings;
    private final BigDecimal maxSingleWeight;
    private final BigDecimal targetInvestRatio;

    public StrategyService(WatchCoinMapper watchCoinMapper,
                           @Value("${strategy.entry-net:0.05}") BigDecimal entryNet,
                           @Value("${strategy.max-holdings:3}") int maxHoldings,
                           @Value("${strategy.max-single-weight:0.40}") BigDecimal maxSingleWeight,
                           @Value("${strategy.target-invest-ratio:0.95}") BigDecimal targetInvestRatio) {
        this.watchCoinMapper = watchCoinMapper;
        this.entryNet = entryNet;
        this.maxHoldings = maxHoldings;
        this.maxSingleWeight = maxSingleWeight;
        this.targetInvestRatio = targetInvestRatio;
    }

    /**
     * 算出目标仓位。
     *
     * @param candidates 按净年化排序的候选
     * @param turnover   各交易对的 24h 成交额（用于权重分配）
     * @param tradable   当前运行模式下**真正能交易**的交易对集合（模拟盘与实盘支持范围不同）；
     *                   不在这里过滤的话，决策日志会列出根本下不了单的币（实测踩过）
     * @return 目标仓位：交易对 → 目标权重（占总资金），空 Map 表示应空仓
     */
    public Map<String, BigDecimal> decideTarget(List<FundingAnalysisService.Candidate> candidates,
                                               Map<String, BigDecimal> turnover,
                                               Set<String> tradable) {
        List<FundingAnalysisService.Candidate> qualified = new ArrayList<>();
        int skippedNotTradable = 0;
        for (FundingAnalysisService.Candidate candidate : candidates) {
            if (tradable != null && !tradable.contains(candidate.symbol())) {
                skippedNotTradable++;
                continue;   // 该币在当前模式下不可交易，直接跳过
            }
            if (candidate.netAnnualized().compareTo(entryNet) >= 0) {
                qualified.add(candidate);
                if (qualified.size() >= maxHoldings) {
                    break;
                }
            }
        }
        if (qualified.isEmpty()) {
            log.info("目标仓位：空仓（当前模式下 {} 个币因不可交易被排除，其余未达建仓门槛 净年化 {}）",
                    skippedNotTradable, percent(entryNet));
            return Map.of();
        }
        log.info("目标仓位：从 {} 个可交易候选中选出 {} 个（另有 {} 个币因当前模式不可交易被排除）",
                tradable == null ? candidates.size() : tradable.size(), qualified.size(), skippedNotTradable);

        Map<String, BigDecimal> target = new LinkedHashMap<>();
        BigDecimal turnoverSum = BigDecimal.ZERO;
        for (FundingAnalysisService.Candidate candidate : qualified) {
            turnoverSum = turnoverSum.add(turnover.getOrDefault(candidate.symbol(), BigDecimal.ZERO));
        }
        for (FundingAnalysisService.Candidate candidate : qualified) {
            BigDecimal weight;
            if (turnoverSum.signum() > 0) {
                weight = turnover.getOrDefault(candidate.symbol(), BigDecimal.ZERO)
                        .divide(turnoverSum, 8, RoundingMode.HALF_UP);
            } else {
                weight = BigDecimal.ONE.divide(BigDecimal.valueOf(qualified.size()), 8, RoundingMode.HALF_UP);
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
     * 取"当前运行模式下可交易"的币种。
     *
     * <p>模拟盘与实盘支持范围不同，标记存在 watch_coin 表里（demo_supported / real_supported），
     * 按模式过滤即可，不需要额外配置。
     *
     * @param paperTrading true = 模拟盘
     * @return 交易对 → 币种信息
     */
    public Map<String, WatchCoin> enabledCoins(boolean paperTrading) {
        Map<String, WatchCoin> map = new LinkedHashMap<>();
        for (WatchCoin coin : watchCoinMapper.findTradable(paperTrading)) {
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
