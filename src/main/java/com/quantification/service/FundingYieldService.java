package com.quantification.service;

import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.InstrumentInfo;
import com.quantification.entity.FundingRateCurrent;
import com.quantification.entity.FundingRateStat;
import com.quantification.mapper.FundingRateCurrentMapper;
import com.quantification.mapper.FundingRateHistoryMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 资金费率收益计算服务。
 *
 * <p>计算口径（重要，别改错）：
 * <ol>
 *   <li><b>毛年化</b>（纯资金费）：把窗口内每次结算的费率<b>正负都累加</b>求平均，
 *       再按该币的实际结算周期折算成年化。只挑正费率算出来的数字是假的。</li>
 *   <li><b>换仓成本年化</b>：按"平均 N 天切换一次币种"，每次切换要平掉旧仓（现货卖出 + 合约买回）
 *       再建新仓（现货买入 + 合约卖出），共 4 笔吃单，成本 = 2 × (现货吃单费率 + 合约吃单费率)，
 *       再按 365/N 折算成年化。</li>
 *   <li><b>净年化</b> = 毛年化 − 换仓成本年化。</li>
 * </ol>
 *
 * <p>合约吃单费率优先取接口返回的真实值（{@code /market/instruments}），取不到时用配置的兜底值；
 * 现货费率暂用配置值（公开接口不返回现货费率，需私有接口 {@code /account/fee-rate}，接入后替换）。
 */
@Service
public class FundingYieldService {

    /** 时间口径与采集服务保持一致。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一年按 365 天算。 */
    private static final BigDecimal DAYS_PER_YEAR = BigDecimal.valueOf(365);

    /** 默认的结算周期（小时），取不到币种实际周期时使用。 */
    private static final int DEFAULT_INTERVAL_HOURS = 8;

    private final BitgetPublicClient bitget;
    private final FundingRateHistoryMapper historyMapper;
    private final FundingRateCurrentMapper currentMapper;

    /** 统计窗口天数，默认 90（接口最多给 90 天）。 */
    private final int lookbackDays;

    /** 平均多少天切换一次币种，默认 45。 */
    private final int rotationDays;

    /** 现货吃单费率，默认 0.001（0.1%，标准档）。 */
    private final BigDecimal spotTakerFeeRate;

    /** 合约吃单费率兜底值，默认 0.0006（0.06%）。 */
    private final BigDecimal perpTakerFeeRateFallback;

    public FundingYieldService(BitgetPublicClient bitget,
                               FundingRateHistoryMapper historyMapper,
                               FundingRateCurrentMapper currentMapper,
                               @Value("${funding-yield.lookback-days:90}") int lookbackDays,
                               @Value("${funding-yield.rotation-days:45}") int rotationDays,
                               @Value("${funding-yield.spot-taker-fee-rate:0.001}") BigDecimal spotTakerFeeRate,
                               @Value("${funding-yield.perp-taker-fee-rate:0.0006}") BigDecimal perpTakerFeeRateFallback) {
        this.bitget = bitget;
        this.historyMapper = historyMapper;
        this.currentMapper = currentMapper;
        this.lookbackDays = lookbackDays;
        this.rotationDays = rotationDays;
        this.spotTakerFeeRate = spotTakerFeeRate;
        this.perpTakerFeeRateFallback = perpTakerFeeRateFallback;
    }

    /**
     * 计算所有币种的收益，按净年化从高到低排序。
     *
     * @return 每个币种一条收益结果
     */
    public List<FundingYield> calculate() {
        LocalDateTime from = LocalDateTime.now(ZONE).minusDays(lookbackDays);
        List<FundingRateStat> stats = historyMapper.summarizeSince(from);

        Map<String, Integer> intervalHours = currentIntervals();
        Map<String, BigDecimal> perpTakerFees = perpTakerFees();

        BigDecimal feeDrag = feeDragAnnualized(perpTakerFees);

        return stats.stream()
                .map(stat -> toYield(stat, intervalHours, feeDrag))
                .sorted((a, b) -> b.netAnnualizedPct().compareTo(a.netAnnualizedPct()))
                .toList();
    }

    /** 把单个币种的统计结果换算成毛年化、成本年化、净年化。 */
    private FundingYield toYield(FundingRateStat stat, Map<String, Integer> intervalHours, BigDecimal feeDrag) {
        int hours = intervalHours.getOrDefault(stat.getSymbol(), DEFAULT_INTERVAL_HOURS);

        // 一年结算次数 = 365 天 × 24 小时 / 结算周期
        BigDecimal settlementsPerYear = DAYS_PER_YEAR
                .multiply(BigDecimal.valueOf(24))
                .divide(BigDecimal.valueOf(hours), 10, RoundingMode.HALF_UP);

        // 毛年化 = 窗口内平均每次费率 × 一年结算次数
        BigDecimal avgRate = stat.getRateSum()
                .divide(BigDecimal.valueOf(stat.getSampleCount()), 18, RoundingMode.HALF_UP);
        BigDecimal grossPct = toPercent(avgRate.multiply(settlementsPerYear));

        return new FundingYield(
                stat.getSymbol(),
                hours,
                stat.getSampleCount(),
                stat.getFirstTime(),
                stat.getLastTime(),
                grossPct,
                toPercent(feeDrag),
                toPercent(avgRate.multiply(settlementsPerYear).subtract(feeDrag)));
    }

    /**
     * 计算换仓成本的年化值。
     *
     * <p>每次切换币种 = 平旧仓（现货卖出 + 合约买回）+ 建新仓（现货买入 + 合约卖出），
     * 共 2 笔现货吃单 + 2 笔合约吃单。
     */
    private BigDecimal feeDragAnnualized(Map<String, BigDecimal> perpTakerFees) {
        BigDecimal perpTaker = perpTakerFees.isEmpty()
                ? perpTakerFeeRateFallback
                : perpTakerFees.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(perpTakerFees.size()), 18, RoundingMode.HALF_UP);

        BigDecimal costPerRotation = spotTakerFeeRate.add(perpTaker).multiply(BigDecimal.valueOf(2));
        return costPerRotation.multiply(DAYS_PER_YEAR)
                .divide(BigDecimal.valueOf(rotationDays), 18, RoundingMode.HALF_UP);
    }

    /** 取每个币的实际结算周期（小时），来自实时费率表。 */
    private Map<String, Integer> currentIntervals() {
        Map<String, Integer> hours = new HashMap<>();
        for (FundingRateCurrent row : currentMapper.findAll()) {
            if (row.getFundingRateInterval() != null) {
                hours.put(row.getSymbol(), row.getFundingRateInterval());
            }
        }
        return hours;
    }

    /** 取各合约的真实吃单费率，取不到时返回空 Map 由调用方用兜底值。 */
    private Map<String, BigDecimal> perpTakerFees() {
        Map<String, BigDecimal> fees = new HashMap<>();
        try {
            for (InstrumentInfo info : bitget.instruments(BitgetPublicClient.USDT_FUTURES)) {
                if (info.takerFeeRate() != null && !info.takerFeeRate().isBlank()) {
                    fees.put(info.symbol(), new BigDecimal(info.takerFeeRate()));
                }
            }
        } catch (Exception e) {
            // 拿不到就用兜底值，不因此让整个计算失败
        }
        return fees;
    }

    /** 小数转百分比数值（0.0839 -> 8.39），保留 2 位。 */
    private static BigDecimal toPercent(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 单个币种的收益结果（数值单位均为百分比，如 5.79 表示 5.79%）。
     *
     * @param symbol              交易对
     * @param intervalHours       结算周期（小时）
     * @param sampleCount         窗口内结算次数
     * @param firstTime           窗口内最早结算时间
     * @param lastTime            窗口内最晚结算时间
     * @param grossAnnualizedPct  毛年化（纯资金费，正负累加）
     * @param feeDragAnnualizedPct 换仓成本年化
     * @param netAnnualizedPct    净年化（毛年化 − 换仓成本）
     */
    public record FundingYield(String symbol,
                               int intervalHours,
                               int sampleCount,
                               LocalDateTime firstTime,
                               LocalDateTime lastTime,
                               BigDecimal grossAnnualizedPct,
                               BigDecimal feeDragAnnualizedPct,
                               BigDecimal netAnnualizedPct) {
    }
}
