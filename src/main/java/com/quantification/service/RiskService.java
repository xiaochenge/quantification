package com.quantification.service;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 风控服务（模块 6）。
 *
 * <p>职责：维护**熔断开关**与账号级风险指标。设计原则：
 * <ul>
 *   <li>熔断是"只进不出"的：一旦触发就停止开新仓，必须<b>人工确认</b>才恢复（配置文件改回 + 重启）；</li>
 *   <li>账户权益峰值与回撤在内存里维护（单机运行，内存状态够用）。</li>
 * </ul>
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    /** 最大回撤阈值（账户权益从峰值回撤超过它 → 全平并停止）。 */
    private final BigDecimal maxDrawdown;

    /** 维持保证金率预警线 / 减仓线（mgnRatio = mmr ÷ effEquity）。 */
    private final BigDecimal mgnRatioWarn;
    private final BigDecimal mgnRatioReduce;

    /** 是否已熔断。 */
    private final AtomicBoolean halted = new AtomicBoolean(false);

    /** 熔断原因。 */
    private final AtomicReference<String> haltReason = new AtomicReference<>("");

    /** 账户权益峰值（USD），用于算回撤。 */
    private final AtomicReference<BigDecimal> equityPeak = new AtomicReference<>(BigDecimal.ZERO);

    public RiskService(@Value("${risk.max-drawdown:0.05}") BigDecimal maxDrawdown,
                       @Value("${risk.mgn-ratio-warn:0.5}") BigDecimal mgnRatioWarn,
                       @Value("${risk.mgn-ratio-reduce:0.8}") BigDecimal mgnRatioReduce) {
        this.maxDrawdown = maxDrawdown;
        this.mgnRatioWarn = mgnRatioWarn;
        this.mgnRatioReduce = mgnRatioReduce;
    }

    /** @return 当前是否熔断 */
    public boolean isHalted() {
        return halted.get();
    }

    /** @return 熔断原因（未熔断时为空串） */
    public String getHaltReason() {
        return haltReason.get();
    }

    /**
     * 触发熔断（只进不出，需要人工介入恢复）。
     *
     * @param reason 原因，会写进日志与告警
     */
    public void halt(String reason) {
        if (halted.compareAndSet(false, true)) {
            haltReason.set(reason);
            log.error("⛔ 触发熔断，停止开新仓。原因：{}", reason);
        }
    }

    /**
     * 用最新账户权益更新峰值并检查回撤。
     *
     * @param accountEquity 账户总权益（USD）
     * @return 当前回撤比例（0.05 表示 5%）
     */
    public BigDecimal updateEquityAndDrawdown(BigDecimal accountEquity) {
        if (accountEquity == null || accountEquity.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal peak = equityPeak.updateAndGet(old -> old.signum() == 0 || accountEquity.compareTo(old) > 0
                ? accountEquity : old);
        BigDecimal drawdown = peak.subtract(accountEquity)
                .divide(peak, 6, java.math.RoundingMode.HALF_UP);
        if (drawdown.compareTo(maxDrawdown) >= 0) {
            halt(String.format("账户权益回撤 %.2f%% 超过阈值 %.2f%%（峰值 %.2f → 当前 %.2f）",
                    drawdown.multiply(BigDecimal.valueOf(100)).doubleValue(),
                    maxDrawdown.multiply(BigDecimal.valueOf(100)).doubleValue(),
                    peak.doubleValue(), accountEquity.doubleValue()));
        }
        return drawdown;
    }

    /**
     * 检查保证金率是否进入预警 / 减仓区。
     *
     * @param mgnRatio 维持保证金率（mmr ÷ effEquity）
     * @return true 表示已到减仓线（调用方应主动减仓）
     */
    public boolean checkMargin(BigDecimal mgnRatio) {
        if (mgnRatio == null || mgnRatio.signum() <= 0) {
            return false;
        }
        if (mgnRatio.compareTo(mgnRatioReduce) >= 0) {
            log.error("⚠️ 维持保证金率 {} 已达减仓线 {}", mgnRatio, mgnRatioReduce);
            return true;
        }
        if (mgnRatio.compareTo(mgnRatioWarn) >= 0) {
            log.warn("⚠️ 维持保证金率 {} 已进入预警区（预警线 {}）", mgnRatio, mgnRatioWarn);
        }
        return false;
    }

    /** @return 账户权益峰值（USD） */
    public BigDecimal getEquityPeak() {
        return equityPeak.get();
    }
}
