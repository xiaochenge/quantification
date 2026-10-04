package com.quantification.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 恢复轮数过滤（{@link FundingAnalysisService#recoveryPassed}）的单元测试。
 *
 * <p>口径与回测脚本 {@code scripts/backtest-funding.py} 的 {@code recovery_ok} 一致：
 * 当前"连续为正"轮数必须超过历史"从负费率恢复到正"的平均轮数才放行。
 */
class RecoveryFilterTest {

    private static List<BigDecimal> rates(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    @DisplayName("没有数据不拦")
    void emptySeriesPasses() {
        assertTrue(FundingAnalysisService.recoveryPassed(List.of()));
    }

    @Test
    @DisplayName("历史上没出现过负费率不拦")
    void allPositivePasses() {
        assertTrue(FundingAnalysisService.recoveryPassed(rates("0.01", "0.02", "0.03")));
    }

    @Test
    @DisplayName("连续为正轮数大于历史恢复均值时放行")
    void longPositiveStreakPasses() {
        // 序列：+ - + + +（升序）。末尾连续为正 3 轮；历史恢复均值 = 1（唯一一次负到正隔 1 轮）。
        assertTrue(FundingAnalysisService.recoveryPassed(rates("0.01", "-0.01", "0.02", "0.03", "0.04")));
    }

    @Test
    @DisplayName("连续为正轮数不大于历史恢复均值时拦住")
    void shortPositiveStreakIsBlocked() {
        // 序列：- +。末尾连续为正 1 轮；历史恢复均值 = 1 → 1 不大于 1，拦下。
        assertFalse(FundingAnalysisService.recoveryPassed(rates("-0.01", "0.01")));
    }
}
