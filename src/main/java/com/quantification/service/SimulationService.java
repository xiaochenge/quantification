package com.quantification.service;

import com.quantification.bitget.BitgetPrivateClient.AccountAssets;
import com.quantification.bitget.BitgetPrivateClient.Position;
import com.quantification.bitget.BitgetPublicClient;
import com.quantification.entity.AccountBalanceSnapshot;
import com.quantification.entity.FundingIncome;
import com.quantification.entity.TradeFill;
import com.quantification.entity.TradeOrder;
import com.quantification.exchange.ExchangeGateway;
import com.quantification.exchange.MockExchangeGateway;
import com.quantification.exchange.MockExchangeGateway.AccountState;
import com.quantification.exchange.MockExchangeGateway.HoldingView;
import com.quantification.exchange.TradeMode;
import com.quantification.mapper.AccountBalanceSnapshotMapper;
import com.quantification.mapper.FundingIncomeMapper;
import com.quantification.mapper.TradeFillMapper;
import com.quantification.mapper.TradeOrderMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 模拟盘账务与查询（模块 9 的服务层）。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>定时结算资金费</b>：调用 {@link ExchangeGateway#settleFunding()}。实盘 / 官方模拟盘
 *       由交易所自己结算（空实现），自建 mock 由它按真实费率 × 真实仓位入账；</li>
 *   <li><b>定时记录净值</b>：把模拟账户的总权益写进 {@code account_balance_snapshot}（source=mock），
 *       这是画净值曲线、算滚动年化的数据源。</li>
 * </ol>
 *
 * <p>另外对外提供只读查询（{@code /api/admin/simulation/*}），后台与人工核对都用它。
 */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    /** 模拟数据的来源标记。 */
    private static final String SOURCE = MockExchangeGateway.SOURCE;

    /** 时间统一按 Asia/Shanghai 处理。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一年的小时数（年化换算用）。 */
    private static final BigDecimal HOURS_PER_YEAR = BigDecimal.valueOf(365L * 24);

    /** 交易网关（自建 mock 模式下就是模拟交易所）。 */
    private final ExchangeGateway exchangeGateway;

    /** 净值快照。 */
    private final AccountBalanceSnapshotMapper snapshotMapper;

    /** 资金费入账（汇总用）。 */
    private final FundingIncomeMapper fundingIncomeMapper;

    /** 成交明细（汇总手续费）。 */
    private final TradeFillMapper tradeFillMapper;

    /** 订单（统计笔数）。 */
    private final TradeOrderMapper tradeOrderMapper;

    /** 默认要展示多少天的滚动年化。 */
    private final int rollingDays;

    public SimulationService(ExchangeGateway exchangeGateway,
                             AccountBalanceSnapshotMapper snapshotMapper,
                             FundingIncomeMapper fundingIncomeMapper,
                             TradeFillMapper tradeFillMapper,
                             TradeOrderMapper tradeOrderMapper,
                             @Value("${simulation.rolling-days:30}") int rollingDays) {
        this.exchangeGateway = exchangeGateway;
        this.snapshotMapper = snapshotMapper;
        this.fundingIncomeMapper = fundingIncomeMapper;
        this.tradeFillMapper = tradeFillMapper;
        this.tradeOrderMapper = tradeOrderMapper;
        this.rollingDays = rollingDays;
    }

    /** 定时结算资金费（默认每 60 秒扫一次，见 application.yml 的 simulation 段）。 */
    @Scheduled(initialDelayString = "${simulation.funding-initial-delay-ms:30000}",
               fixedDelayString = "${simulation.funding-settle-interval-ms:60000}")
    public void scheduledSettleFunding() {
        try {
            exchangeGateway.settleFunding();
        } catch (Exception e) {
            // 结算失败不能打断调度：下一个周期会重试（结算本身是幂等的）
            log.warn("资金费结算异常（下个周期重试）：{}", e.getMessage());
        }
    }

    /** 定时记录模拟净值（默认每小时一次）。 */
    @Scheduled(initialDelayString = "${simulation.snapshot-initial-delay-ms:30000}",
               fixedDelayString = "${simulation.snapshot-interval-ms:3600000}")
    public void scheduledSnapshot() {
        try {
            snapshot();
        } catch (Exception e) {
            log.warn("记录模拟净值失败（下个周期重试）：{}", e.getMessage());
        }
    }

    /**
     * 立刻记录一条模拟净值快照。
     *
     * @return 写入的快照；当前不是自建 mock 模式时返回 null
     */
    public AccountBalanceSnapshot snapshot() {
        if (exchangeGateway.mode() != TradeMode.MOCK) {
            return null;
        }
        AccountState state = ((MockExchangeGateway) exchangeGateway).state();
        AccountAssets assets = exchangeGateway.assets();
        BigDecimal unrealised = BigDecimal.ZERO;
        for (Position position : exchangeGateway.positions(BitgetPublicClient.USDT_FUTURES, null)) {
            unrealised = unrealised.add(decimal(position.unrealisedPnl()));
        }
        AccountBalanceSnapshot row = new AccountBalanceSnapshot();
        row.setSource(SOURCE);
        row.setAccountEquityUsd(decimal(assets.accountEquity()));
        // 模拟账户里 USDT 现金就是"USDT 权益"（现货与合约浮盈亏单独记在下面两个字段）
        row.setUsdtEquity(state.cash());
        row.setUnrealisedPnlUsd(unrealised);
        row.setEffEquity(decimal(assets.effEquity()));
        row.setMmr(decimal(assets.mmr()));
        row.setMgnRatio(decimal(assets.mgnRatio()));
        row.setPositionValue(decimal(assets.positionValue()));
        row.setSourceTime(LocalDateTime.now(ZONE));
        snapshotMapper.insert(row);
        log.info("模拟净值快照：总权益 {} USDT，有效权益 {}，仓位价值 {}，维持保证金率 {}",
                assets.accountEquity(), assets.effEquity(), assets.positionValue(), assets.mgnRatio());
        return row;
    }

    /**
     * 模拟盘当前状态（后台与人工核对用）。
     *
     * @return 状态汇总
     */
    public SimulationStatus status() {
        TradeMode mode = exchangeGateway.mode();
        BigDecimal fundingIncome = trim(fundingIncomeMapper.sumAmount(SOURCE));
        BigDecimal feeCost = trim(tradeFillMapper.sumFee(SOURCE));
        int orders = tradeOrderMapper.countBySource(SOURCE);
        int fills = tradeFillMapper.countBySource(SOURCE);
        if (mode != TradeMode.MOCK) {
            return new SimulationStatus(mode.getLabel(), false, null, null, null, null,
                    fundingIncome, feeCost, orders, fills, List.of(), null, null,
                    "当前模式是" + mode.getLabel() + "，自建 mock 未启用（把 simulation.enabled 设为 true 才生效）");
        }
        AccountState state = ((MockExchangeGateway) exchangeGateway).state();
        AccountAssets assets = exchangeGateway.assets();
        BigDecimal equity = trim(decimal(assets.accountEquity()));
        BigDecimal initial = state.initialUsdt();
        BigDecimal cumulative = initial.signum() > 0
                ? equity.divide(initial, 8, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                : null;
        return new SimulationStatus(mode.getLabel(), true, state.cash(), initial, equity,
                trim(decimal(assets.effEquity())), fundingIncome, feeCost, orders, fills, state.holdings(),
                rollingAnnualized(rollingDays), cumulative, "");
    }

    /**
     * 查模拟净值曲线。
     *
     * @param limit 取多少条（新的在前）
     * @return 净值快照列表
     */
    public List<AccountBalanceSnapshot> equityCurve(int limit) {
        return snapshotMapper.findRecent(SOURCE, limit);
    }

    /**
     * 算滚动年化（简单年化：{@code (期末 ÷ 期初 − 1) ÷ 年数}）。
     *
     * <p>为什么用单利而不是复利：窗口通常只有几十天，两者差异极小，而单利全程用
     * {@link BigDecimal} 算得出来（复利要开分数次方，会引入 {@code double}，
     * 资金相关的地方一律不用浮点）。
     *
     * @param days 窗口天数（从"该窗口内最早的一条快照"算起）
     * @return 年化收益率（小数）；快照不足两条或时间跨度太短时返回 null
     */
    public BigDecimal rollingAnnualized(int days) {
        List<AccountBalanceSnapshot> latest = snapshotMapper.findRecent(SOURCE, 1);
        if (latest.isEmpty()) {
            return null;
        }
        AccountBalanceSnapshot end = latest.get(0);
        AccountBalanceSnapshot start = snapshotMapper.findFirstSince(SOURCE,
                LocalDateTime.now(ZONE).minusDays(days));
        if (start == null || start.getSourceTime() == null || end.getSourceTime() == null
                || start.getAccountEquityUsd() == null || end.getAccountEquityUsd() == null
                || start.getAccountEquityUsd().signum() <= 0
                || !end.getSourceTime().isAfter(start.getSourceTime())) {
            return null;
        }
        long hours = Duration.between(start.getSourceTime(), end.getSourceTime()).toHours();
        if (hours < 24) {
            return null;   // 不足一天就年化，数字没有意义
        }
        BigDecimal years = BigDecimal.valueOf(hours).divide(HOURS_PER_YEAR, 12, RoundingMode.HALF_UP);
        BigDecimal ratio = end.getAccountEquityUsd()
                .divide(start.getAccountEquityUsd(), 12, RoundingMode.HALF_UP);
        return ratio.subtract(BigDecimal.ONE).divide(years, 8, RoundingMode.HALF_UP);
    }

    /** @return 默认滚动窗口天数（配置项 simulation.rolling-days） */
    public int getRollingDays() {
        return rollingDays;
    }

    /**
     * 立刻结算一次资金费（调试用；正式运行由定时任务负责）。
     *
     * @return 说明文本
     */
    public String settleFundingNow() {
        exchangeGateway.settleFunding();
        return "已触发一次资金费结算（当前模式：" + exchangeGateway.mode().getLabel() + "）";
    }

    /**
     * 查模拟成交明细（按时间倒序，用于核对手续费与滑点）。
     *
     * @param limit 取多少条
     * @return 成交明细列表
     */
    public List<TradeFill> recentFills(int limit) {
        return tradeFillMapper.findRecent(SOURCE, limit);
    }

    /**
     * 查模拟资金费入账明细（按时间倒序，用于核对资金费收益）。
     *
     * @param limit 取多少条
     * @return 资金费入账列表
     */
    public List<FundingIncome> recentFundingIncome(int limit) {
        return fundingIncomeMapper.findRecent(SOURCE, limit);
    }

    /**
     * 查模拟订单（按时间倒序，用于核对下单与成交状态）。
     *
     * @param limit 取多少条
     * @return 订单列表
     */
    public List<TradeOrder> recentOrders(int limit) {
        return tradeOrderMapper.findRecent(SOURCE, limit);
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /**
     * 去掉多余尾零，让接口返回的数字好读。
     *
     * <p>三个原因：SQL 的 {@code COALESCE(SUM(...), 0)} 会给出 {@code 0E-18}；
     * {@code stripTrailingZeros()} 会把 10000 变成 {@code 1E+4}（scale 为负）；
     * 而账务平均会带出一长串小数位。加 {@code BigDecimal.ZERO} 能把 scale 拉回非负，
     * 于是 0 就是 0、10000 就是 10000。
     *
     * @param value 原始值
     * @return 规整后的值；入参为 null 时返回 null
     */
    private static BigDecimal trim(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().add(BigDecimal.ZERO);
    }

    /**
     * 模拟盘状态汇总。
     *
     * @param mode            当前模式名称
     * @param mockEnabled     自建 mock 是否启用
     * @param cash            模拟账户现金（USDT）
     * @param initialUsdt     模拟账户初始资金（USDT）
     * @param accountEquity   模拟账户总权益（USDT）
     * @param effEquity       有效权益（打折后可作保证金的净值）
     * @param fundingIncome   累计资金费收入（USDT）
     * @param feeCost         累计手续费支出（USDT）
     * @param orderCount      模拟订单数
     * @param fillCount       模拟成交笔数
     * @param holdings        模拟持仓明细
     * @param rollingAnnualized 滚动年化（小数，数据不足时为 null）
     * @param cumulativeReturn  累计收益率（小数）
     * @param note            说明（比如"当前不是 mock 模式"）
     */
    public record SimulationStatus(String mode, boolean mockEnabled, BigDecimal cash, BigDecimal initialUsdt,
                                   BigDecimal accountEquity, BigDecimal effEquity, BigDecimal fundingIncome,
                                   BigDecimal feeCost, int orderCount, int fillCount, List<HoldingView> holdings,
                                   BigDecimal rollingAnnualized, BigDecimal cumulativeReturn, String note) {
    }
}
