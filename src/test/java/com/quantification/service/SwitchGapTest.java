package com.quantification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;


import com.quantification.service.FundingAnalysisService.Candidate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StrategyService#decideTarget} 的换仓滞回（switch-gap）单元测试。
 *
 * <p>为什么重要：换一次仓要付双边手续费 + 买卖价差（实测约占名义额的 0.25%），
 * 而多赚的那点资金费是按天摊的。门槛太松 → 天天换、摩擦成本吃掉全部收益（实测就是这样）；
 * 门槛太严 → 该换的也不换、资金费白白少赚。所以用固定场景把边界钉死。
 */
class SwitchGapTest {

    private static final BigDecimal ENTRY_NET = new BigDecimal("0.05");
    private static final BigDecimal SWITCH_GAP = new BigDecimal("0.10");
    private static final int MIN_HOLDING_DAYS = 7;

    private static StrategyService service() {
        return new StrategyService(null, analysisAllPassing(),
                ENTRY_NET, 3, new BigDecimal("0.40"), new BigDecimal("0.95"),
                SWITCH_GAP, MIN_HOLDING_DAYS);
    }

    /** 全部持仓都"早就开好了"，最短持有期不参与判断。 */
    private static Map<String, LocalDateTime> openedLongAgo(String... symbols) {
        return openedDaysAgo(30, symbols);
    }

    /** 指定持仓是 N 天前开的。 */
    private static Map<String, LocalDateTime> openedDaysAgo(int days, String... symbols) {
        LocalDateTime opened = LocalDateTime.now().minusDays(days);
        Map<String, LocalDateTime> map = new HashMap<>();
        for (String symbol : symbols) {
            map.put(symbol, opened);
        }
        return map;
    }

    /**
     * 恢复轮数过滤全放行的替身。
     *
     * <p>本用例只关心换仓滞回，恢复过滤另有测试；用手写替身而不是 Mockito，
     * 是为了让本项目的测试保持"纯 JUnit、不引额外依赖"的风格。
     * {@code decideTarget} 也不读 {@link WatchCoinMapper}，所以那个依赖直接传 null。
     */
    private static FundingAnalysisService analysisAllPassing() {
        return new FundingAnalysisService(null, null, 10,
                new BigDecimal("0.0006"), new BigDecimal("0.000375"), 45, true) {
            @Override
            public boolean recoveryFilterPassed(String symbol) {
                return true;
            }
        };
    }

    /** 造一个候选：毛年化 = 净年化（测试不关心两者差异）。 */
    private static Candidate candidate(String symbol, String netAnnualized) {
        BigDecimal net = new BigDecimal(netAnnualized);
        return new Candidate(symbol, 8, 100, net, net);
    }

    @Test
    @DisplayName("满仓 + 新币只比最差持仓高 2.5 个点（不足 4 个点门槛）→ 不换")
    void fullBookBelowGapKeepsEverything() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("AUSDT", "0.20"), candidate("BUSDT", "0.15"),
                        candidate("DUSDT", "0.145"), candidate("CUSDT", "0.12")),
                Map.of(), Set.of("AUSDT", "BUSDT", "CUSDT", "DUSDT"),
                Set.of("AUSDT", "BUSDT", "CUSDT"),
                openedLongAgo("AUSDT", "BUSDT", "CUSDT"));

        assertEquals(Set.of("AUSDT", "BUSDT", "CUSDT"), target.keySet());
    }

    @Test
    @DisplayName("满仓 + 新币比最差持仓高 13 个点（达到门槛）→ 换掉最差的那个")
    void fullBookAboveGapSwapsWorst() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("DUSDT", "0.25"), candidate("AUSDT", "0.20"),
                        candidate("BUSDT", "0.15"), candidate("CUSDT", "0.12")),
                Map.of(), Set.of("AUSDT", "BUSDT", "CUSDT", "DUSDT"),
                Set.of("AUSDT", "BUSDT", "CUSDT"),
                openedLongAgo("AUSDT", "BUSDT", "CUSDT"));

        assertEquals(Set.of("AUSDT", "BUSDT", "DUSDT"), target.keySet());
    }

    @Test
    @DisplayName("有空位 → 直接补最好的新币，不适用换仓门槛（这是加仓不是换仓）")
    void freeSlotFillsWithoutGap() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("AUSDT", "0.20"), candidate("CUSDT", "0.12"), candidate("BUSDT", "0.06")),
                Map.of(), Set.of("AUSDT", "BUSDT", "CUSDT"),
                Set.of("AUSDT"),
                openedLongAgo("AUSDT"));

        assertEquals(Set.of("AUSDT", "CUSDT", "BUSDT"), target.keySet());
    }

    @Test
    @DisplayName("持仓跌破建仓门槛 → 不进目标名单（会被无条件平掉，不受滞回保护）")
    void holdingBelowEntryThresholdIsDropped() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("AUSDT", "0.20"), candidate("BUSDT", "0.15"),
                        candidate("DUSDT", "0.10"), candidate("CUSDT", "0.03")),
                Map.of(), Set.of("AUSDT", "BUSDT", "CUSDT", "DUSDT"),
                Set.of("AUSDT", "BUSDT", "CUSDT"),
                openedLongAgo("AUSDT", "BUSDT", "CUSDT"));

        assertEquals(Set.of("AUSDT", "BUSDT", "DUSDT"), target.keySet());
    }

    @Test
    @DisplayName("持仓掉出篮子（不可交易）→ 不进目标名单（会被无条件平掉）")
    void holdingNoLongerTradableIsDropped() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("AUSDT", "0.20"), candidate("BUSDT", "0.15"),
                        candidate("CUSDT", "0.12"), candidate("DUSDT", "0.10")),
                Map.of(), Set.of("AUSDT", "BUSDT", "DUSDT"),
                Set.of("AUSDT", "BUSDT", "CUSDT"),
                openedLongAgo("AUSDT", "BUSDT", "CUSDT"));

        assertEquals(Set.of("AUSDT", "BUSDT", "DUSDT"), target.keySet());
    }

    @Test
    @DisplayName("全部候选都跌破门槛 → 空仓")
    void nothingQualifiesGoesFlat() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("AUSDT", "0.04"), candidate("BUSDT", "0.01")),
                Map.of(), Set.of("AUSDT", "BUSDT"), Set.of("AUSDT"), openedLongAgo("AUSDT"));

        assertEquals(Set.of(), target.keySet());
    }

    @Test
    @DisplayName("候选领先足够多，但持仓都没满最短持有期 → 先不换")
    void minHoldingPeriodBlocksEvenHugeGap() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("DUSDT", "0.30"), candidate("AUSDT", "0.20"),
                        candidate("BUSDT", "0.15"), candidate("CUSDT", "0.12")),
                Map.of(), Set.of("AUSDT", "BUSDT", "CUSDT", "DUSDT"),
                Set.of("AUSDT", "BUSDT", "CUSDT"),
                openedDaysAgo(1, "AUSDT", "BUSDT", "CUSDT"));   // 昨天刚开

        assertEquals(Set.of("AUSDT", "BUSDT", "CUSDT"), target.keySet());
    }

    @Test
    @DisplayName("最短持有期只保护还没到期的那些：刚开的挡住，拿够天数的最差持仓照换不误")
    void minHoldingPeriodStillSwapsTheMaturedOne() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("AUSDT", "0.20"), candidate("BUSDT", "0.15"),
                        candidate("CUSDT", "0.23"), candidate("DUSDT", "0.12")),
                Map.of(), Set.of("AUSDT", "BUSDT", "CUSDT", "DUSDT"),
                Set.of("AUSDT", "BUSDT", "DUSDT"),
                Map.of("AUSDT", LocalDateTime.now().minusDays(1),   // 刚开：不许换
                        "BUSDT", LocalDateTime.now().minusDays(30),
                        "DUSDT", LocalDateTime.now().minusDays(30)));

        // DUSDT(12%) 是最差、且已拿够 7 天 → 被 CUSDT(23%) 换掉；刚开的 AUSDT 不受影响
        assertEquals(Set.of("AUSDT", "BUSDT", "CUSDT"), target.keySet());
    }

    @Test
    @DisplayName("拿不到开仓时间（如实盘）→ 最短持有期不生效，不能把仓位锁死")
    void unknownOpenTimeDoesNotBlockSwaps() {
        Map<String, BigDecimal> target = service().decideTarget(
                List.of(candidate("DUSDT", "0.25"), candidate("AUSDT", "0.20"),
                        candidate("BUSDT", "0.15"), candidate("CUSDT", "0.12")),
                Map.of(), Set.of("AUSDT", "BUSDT", "CUSDT", "DUSDT"),
                Set.of("AUSDT", "BUSDT", "CUSDT"),
                Map.of());

        assertEquals(Set.of("AUSDT", "BUSDT", "DUSDT"), target.keySet());
    }
}
