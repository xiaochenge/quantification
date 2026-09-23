package com.quantification.service;

import com.quantification.entity.FundingRateCurrent;
import com.quantification.entity.FundingRateStat;
import com.quantification.mapper.FundingRateCurrentMapper;
import com.quantification.mapper.FundingRateHistoryMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 费率分析（模块 3）：把历史费率算成"净年化"，排出候选币。
 *
 * <p>口径（与回测脚本 `scripts/backtest-funding.py` 保持一致）：
 * <ol>
 *   <li><b>毛年化</b>：窗口内每次结算的费率<b>正负累加</b>取平均，再按该币实际结算周期折年化；</li>
 *   <li><b>净年化</b> = 毛年化 − 年化换仓成本（轮换周期 × 双边吃单）。</li>
 * </ol>
 * 注意：绝不能只挑正费率算，否则数字是假的。
 */
@Service
public class FundingAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(FundingAnalysisService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal HOURS_PER_YEAR = BigDecimal.valueOf(365L * 24);
    private static final int DEFAULT_INTERVAL_HOURS = 8;

    private final FundingRateHistoryMapper historyMapper;
    private final FundingRateCurrentMapper currentMapper;
    private final int lookbackDays;
    private final BigDecimal spotTakerFeeRate;
    private final BigDecimal perpTakerFeeRate;
    private final int rotationDays;

    public FundingAnalysisService(FundingRateHistoryMapper historyMapper,
                                  FundingRateCurrentMapper currentMapper,
                                  @Value("${strategy.lookback-days:10}") int lookbackDays,
                                  @Value("${funding-yield.spot-taker-fee-rate:0.0006}") BigDecimal spotTakerFeeRate,
                                  @Value("${funding-yield.perp-taker-fee-rate:0.000375}") BigDecimal perpTakerFeeRate,
                                  @Value("${funding-yield.rotation-days:45}") int rotationDays) {
        this.historyMapper = historyMapper;
        this.currentMapper = currentMapper;
        this.lookbackDays = lookbackDays;
        this.spotTakerFeeRate = spotTakerFeeRate;
        this.perpTakerFeeRate = perpTakerFeeRate;
        this.rotationDays = rotationDays;
    }

    /**
     * 按净年化排序列出候选币。
     *
     * @return 候选列表（净年化从高到低），数据不足一个完整窗口的币不参与
     */
    public List<Candidate> rankCandidates() {
        LocalDateTime from = LocalDateTime.now(ZONE).minusDays(lookbackDays);
        List<FundingRateStat> stats = historyMapper.summarizeSince(from);

        Map<String, Integer> intervals = new HashMap<>();
        for (FundingRateCurrent current : currentMapper.findAll()) {
            if (current.getFundingRateInterval() != null) {
                intervals.put(current.getSymbol(), current.getFundingRateInterval());
            }
        }

        BigDecimal annualCost = annualRotationCost();
        List<Candidate> candidates = new ArrayList<>();
        for (FundingRateStat stat : stats) {
            if (stat.getSampleCount() == null || stat.getSampleCount() < lookbackDays) {
                continue;   // 数据不足一个窗口，不参与判断
            }
            int hours = intervals.getOrDefault(stat.getSymbol(), DEFAULT_INTERVAL_HOURS);
            BigDecimal settlementsPerYear = HOURS_PER_YEAR.divide(BigDecimal.valueOf(hours), 6, RoundingMode.HALF_UP);
            BigDecimal avgRate = stat.getRateSum()
                    .divide(BigDecimal.valueOf(stat.getSampleCount()), 18, RoundingMode.HALF_UP);
            BigDecimal gross = avgRate.multiply(settlementsPerYear);
            candidates.add(new Candidate(stat.getSymbol(), hours, stat.getSampleCount(),
                    gross, gross.subtract(annualCost)));
        }
        candidates.sort(Comparator.comparing(Candidate::netAnnualized).reversed());
        return candidates;
    }

    /** @return 年化换仓成本 = 2 × (现货吃单 + 合约吃单) × 365 / 轮换天数 */
    public BigDecimal annualRotationCost() {
        return spotTakerFeeRate.add(perpTakerFeeRate)
                .multiply(BigDecimal.valueOf(2))
                .multiply(BigDecimal.valueOf(365))
                .divide(BigDecimal.valueOf(rotationDays), 8, RoundingMode.HALF_UP);
    }

    /** @return 当前使用的窗口天数（配置项） */
    public int getLookbackDays() {
        return lookbackDays;
    }

    /**
     * 一个候选币的分析结果。
     *
     * @param symbol          交易对
     * @param intervalHours   结算周期（小时）
     * @param sampleCount     窗口内结算次数
     * @param grossAnnualized 毛年化（纯资金费，正负累加）
     * @param netAnnualized   净年化（毛年化 − 换仓成本年化）
     */
    public record Candidate(String symbol, int intervalHours, int sampleCount,
                            BigDecimal grossAnnualized, BigDecimal netAnnualized) {
    }
}
