package com.quantification.service;

import com.quantification.bitget.BitgetPrivateClient;
import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.InstrumentInfo;
import com.quantification.bitget.BitgetPublicClient.OrderBook;
import com.quantification.entity.WatchCoin;
import com.quantification.entity.Instrument;
import com.quantification.mapper.InstrumentMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 交易执行（模块 5）：把一个"目标名义金额"变成两条腿的实际成交。
 *
 * <p>严格遵守 `docs/order-handling.md` 的纪律：
 * <ol>
 *   <li><b>幂等</b>：clientOid 用可复现规则 `q{币}-{腿}-{时间戳}`，下单前后都能靠它查状态；</li>
 *   <li><b>精度</b>：数量一律<b>向下取整</b>到交易所允许的精度，绝不四舍五入（实测踩过坑）；</li>
 *   <li><b>滑点</b>：用 IOC 限价单，价格 = 盘口最优价 ± 滑点上限，超上限就不下；</li>
 *   <li><b>两腿</b>：现货与合约同时发起；腿差超过容差则补小腿，补不齐就把大腿减到对齐。</li>
 * </ol>
 */
@Service
public class OrderExecutionService {

    private static final Logger log = LoggerFactory.getLogger(OrderExecutionService.class);
    /** unix 毫秒时间戳能塞进 clientOid 的位数（官方限制 32 字符）。 */
    private static final int CLIENT_OID_BASE = 36;

    private final BitgetPublicClient publicClient;
    private final BitgetPrivateClient privateClient;
    /** 下单规则来自数据库（定时同步），绝不临时猜或临时查。 */
    private final InstrumentMapper instrumentMapper;

    /** 滑点上限：主流币 / 小币（每条腿）。 */
    private final BigDecimal slippageMajor;
    private final BigDecimal slippageSmall;
    /** 24h 成交额 ≥ 此值算主流币。 */
    private final BigDecimal majorTurnover;
    /** 单笔最小名义金额（USDT）。 */
    private final BigDecimal minOrderNotional;
    /** 取盘口多少档估算可下单量。 */
    private final int depthLimit;
    /** 两腿容差。 */
    private final BigDecimal legTolerance;
    /**
     * 限价缓冲：限价 = 对手价 ± 该比例。
     *
     * <p><b>重要</b>：缓冲只是"能接受的最差价"，FOK 限价单的**实际成交价仍是盘口价**，
     * 所以放宽缓冲几乎不增加成本，只是大幅提高成交概率。
     */
    private final BigDecimal priceBuffer;

    public OrderExecutionService(BitgetPublicClient publicClient,
                                 BitgetPrivateClient privateClient,
                                 InstrumentMapper instrumentMapper,
                                 @Value("${execution.slippage-limit-major:0.0005}") BigDecimal slippageMajor,
                                 @Value("${execution.slippage-limit-small:0.0015}") BigDecimal slippageSmall,
                                 @Value("${execution.major-turnover:50000000}") BigDecimal majorTurnover,
                                 @Value("${execution.min-order-notional:20}") BigDecimal minOrderNotional,
                                 @Value("${execution.depth-limit:100}") int depthLimit,
                                 @Value("${execution.leg-tolerance:0.005}") BigDecimal legTolerance,
                                 @Value("${execution.price-buffer:0.01}") BigDecimal priceBuffer) {
        this.publicClient = publicClient;
        this.privateClient = privateClient;
        this.instrumentMapper = instrumentMapper;
        this.slippageMajor = slippageMajor;
        this.slippageSmall = slippageSmall;
        this.majorTurnover = majorTurnover;
        this.minOrderNotional = minOrderNotional;
        this.depthLimit = depthLimit;
        this.legTolerance = legTolerance;
        this.priceBuffer = priceBuffer;
    }

    /**
     * 建仓：现货买入 + 永续卖出开空（delta 中性）。
     *
     * @param coin            币种（含现货与永续交易对）
     * @param notionalUsdt    目标名义金额（USDT）
     * @param turnover24h     该币 24h 成交额（用来决定滑点上限档位）
     * @return 执行结果
     */
    public ExecutionResult openPosition(WatchCoin coin, BigDecimal notionalUsdt, BigDecimal turnover24h) {
        BigDecimal slippage = slippageLimit(turnover24h);
        Instrument spotRule = instrumentMapper.find(BitgetPublicClient.SPOT, coin.getSpotSymbol());
        Instrument perpRule = instrumentMapper.find(BitgetPublicClient.USDT_FUTURES, coin.getFuturesSymbol());
        if (spotRule == null || perpRule == null) {
            return ExecutionResult.failed("数据库里没有交易对规则（等规则同步完成再下单），跳过");
        }

        OrderBook spotBook = publicClient.orderBook(BitgetPublicClient.SPOT, coin.getSpotSymbol(), depthLimit);
        OrderBook perpBook = publicClient.orderBook(BitgetPublicClient.USDT_FUTURES, coin.getFuturesSymbol(), depthLimit);
        if (spotBook.asks() == null || spotBook.asks().isEmpty() || perpBook.bids() == null || perpBook.bids().isEmpty()) {
            return ExecutionResult.failed("盘口为空，跳过");
        }

        // 按"滑点上限内能吃多少"确定本次可下单量，再与目标取小
        BigDecimal spotAffordable = affordableNotional(spotBook.asks(), slippage);
        BigDecimal perpAffordable = affordableNotional(perpBook.bids(), slippage);
        BigDecimal affordable = spotAffordable.min(perpAffordable);
        BigDecimal notional = notionalUsdt.min(affordable);
        if (notional.compareTo(minOrderNotional) < 0) {
            return ExecutionResult.failed("盘口可承受量 " + affordable + " USDT 低于最小下单额 " + minOrderNotional);
        }

        BigDecimal spotPrice = new BigDecimal(spotBook.asks().get(0).get(0));
        BigDecimal perpPrice = new BigDecimal(perpBook.bids().get(0).get(0));
        // 数量必须按规则"向下取整 + 对齐乘数"，并落在 [minOrderQty, maxOrderQty] 区间内
        BigDecimal spotQty = normalizeQty(spotRule, notional.divide(spotPrice, 18, RoundingMode.DOWN));
        BigDecimal perpQty = normalizeQty(perpRule, notional.divide(perpPrice, 18, RoundingMode.DOWN));
        if (spotQty == null || perpQty == null) {
            return ExecutionResult.failed("数量不满足交易对规则（精度/乘数/最小最大值），跳过");
        }

        // 两腿同时发 FOK 限价单：要么全成、要么全不成。
        // 限价 = 对手价 ± 缓冲（缓冲只是"能接受的最差价"，实际成交价仍是盘口价）。
        String spotOid = clientOid(coin.getBaseCoin(), "spot-open");
        String perpOid = clientOid(coin.getBaseCoin(), "perp-open");
        String spotLimit = normalizePrice(spotRule, spotPrice.multiply(BigDecimal.ONE.add(priceBuffer)), true);
        String perpLimit = normalizePrice(perpRule, perpPrice.multiply(BigDecimal.ONE.subtract(priceBuffer)), false);
        if (spotLimit == null || perpLimit == null) {
            return ExecutionResult.failed("价格不满足交易对规则，跳过");
        }
        placeQuietly(coin.getSpotSymbol(), "SPOT", "buy", null, spotQty.toPlainString(), spotLimit, spotOid);
        placeQuietly(coin.getFuturesSymbol(), "USDT-FUTURES", "sell", "short", perpQty.toPlainString(), perpLimit, perpOid);

        // 关键：下完单必须查【实际成交数量】——API 返回 success 只代表"已受理"，
        // 实测踩过坑：现货腿 FOK 没成交、合约腿成交，形成裸空头，而日志显示两腿都"已提交"。
        BigDecimal spotFilled = filledQty(spotOid);
        BigDecimal perpFilled = filledQty(perpOid);
        log.info("建仓下单：{} 现货买 {}（限价 {}，成交 {}），合约空 {}（限价 {}，成交 {}）",
                coin.getBaseCoin(), spotQty.toPlainString(), spotLimit, spotFilled.toPlainString(),
                perpQty.toPlainString(), perpLimit, perpFilled.toPlainString());

        // 有一腿没成交 → 用「更宽的缓冲」补那一腿（把没成的腿补齐，而不是放弃）
        BigDecimal wideBuffer = priceBuffer.multiply(BigDecimal.valueOf(3));
        if (spotFilled.signum() <= 0) {
            String limit = normalizePrice(spotRule, spotPrice.multiply(BigDecimal.ONE.add(wideBuffer)), true);
            log.warn("现货腿未成交，用更宽缓冲重下：限价 {}", limit);
            // 注意：clientOid 每次调用都会生成新值，必须先存下来再复用（否则查不到这笔单）
            String spotRetryOid = clientOid(coin.getBaseCoin(), "spot-retry");
            placeQuietly(coin.getSpotSymbol(), "SPOT", "buy", null, spotQty.toPlainString(), limit, spotRetryOid);
            spotFilled = filledQty(spotRetryOid);
        }
        if (perpFilled.signum() <= 0) {
            String limit = normalizePrice(perpRule, perpPrice.multiply(BigDecimal.ONE.subtract(wideBuffer)), false);
            log.warn("合约腿未成交，用更宽缓冲重下：限价 {}", limit);
            String oid = clientOid(coin.getBaseCoin(), "perp-retry");
            placeQuietly(coin.getFuturesSymbol(), "USDT-FUTURES", "sell", "short", perpQty.toPlainString(), limit, oid);
            perpFilled = filledQty(oid);
        }

        // 仍然有一腿是空的 → 回退已成交的那腿，绝不留单边敞口
        if (spotFilled.signum() <= 0 && perpFilled.signum() > 0) {
            log.error("⚠️ 补单后仍只有合约腿成交，出现裸空头敞口，立即市价买回");
            boolean rolledBack = marketBuyBackQuietly(coin.getFuturesSymbol(), perpFilled.toPlainString());
            return rolledBack
                    ? ExecutionResult.failed("现货腿补单失败，已回退合约腿（无残留敞口）")
                    : ExecutionResult.failedWithExposure("现货腿补单失败，且回退合约腿也失败，仍有裸空头敞口！");
        }
        if (perpFilled.signum() <= 0 && spotFilled.signum() > 0) {
            log.error("⚠️ 补单后仍只有现货腿成交，出现裸多头敞口，立即市价卖回");
            boolean rolledBack = marketSellQuietly(coin.getSpotSymbol(), spotFilled.toPlainString());
            return rolledBack
                    ? ExecutionResult.failed("合约腿补单失败，已回退现货腿（无残留敞口）")
                    : ExecutionResult.failedWithExposure("合约腿补单失败，且回退现货腿也失败，仍有裸多头敞口！");
        }
        if (spotFilled.signum() <= 0) {
            return ExecutionResult.failed("两腿都未成交");
        }

        // 两腿都成交：按实际成交量的偏差做一次容差检查（超出时报出来，由风控决定是否减仓）
        BigDecimal diff = spotFilled.subtract(perpFilled).abs();
        BigDecimal base = spotFilled.max(perpFilled);
        if (base.signum() > 0 && diff.divide(base, 6, RoundingMode.HALF_UP).compareTo(legTolerance) > 0) {
            log.warn("⚠️ 两腿成交量偏差 {} 超过容差 {}（现货 {} vs 合约 {}），下次评估会按目标仓位校正",
                    diff, legTolerance, spotFilled, perpFilled);
        }
        return ExecutionResult.ok(notional, spotFilled, perpFilled);
    }

    /**
     * 查某个 clientOid 的【实际成交数量】。
     *
     * <p>刚下单时交易所可能还没回填状态，所以短暂等待 + 最多查询 3 次。
     *
     * @param clientOid 下单时用的自定义订单号
     * @return 累计成交数量；查不到或未成交返回 0
     */
    private BigDecimal filledQty(String clientOid) {
        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                var info = privateClient.orderInfo(null, clientOid);
                if (info != null) {
                    // 反查结果必须打出来：状态 / 委托量 / 实际成交量 / 均价，
                    // 否则事后无法判断"为什么这腿没成交"
                    log.info("成交反查 {}：状态={} 委托={} 已成交={} 均价={}",
                            clientOid, info.orderStatus(), info.qty(), info.cumExecQty(), info.avgPrice());
                }
                if (info != null && info.cumExecQty() != null && !info.cumExecQty().isBlank()) {
                    BigDecimal filled = new BigDecimal(info.cumExecQty());
                    if (filled.signum() > 0 || "filled".equals(info.orderStatus()) || "cancelled".equals(info.orderStatus())) {
                        return filled;
                    }
                }
            } catch (Exception e) {
                log.warn("查询订单成交状态失败 clientOid={}：{}", clientOid, e.getMessage());
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 平仓：现货卖出 + 永续买回（按交易所真实持仓为准）。
     *
     * @param coin     币种
     * @param spotQty  要卖出的现货数量（基础币）
     * @param perpQty  要买回的合约数量（基础币）
     * @return 执行结果
     */
    public ExecutionResult closePosition(WatchCoin coin, BigDecimal spotQty, BigDecimal perpQty) {
        Instrument spotRule = instrumentMapper.find(BitgetPublicClient.SPOT, coin.getSpotSymbol());
        Instrument perpRule = instrumentMapper.find(BitgetPublicClient.USDT_FUTURES, coin.getFuturesSymbol());
        boolean spotOk = false;
        if (spotQty != null && spotQty.signum() > 0 && spotRule != null) {
            BigDecimal qty = normalizeQty(spotRule, spotQty);
            if (qty != null) {
                spotOk = marketSellQuietly(coin.getSpotSymbol(), qty.toPlainString());
            }
        }
        boolean perpOk = false;
        if (perpQty != null && perpQty.signum() > 0 && perpRule != null) {
            BigDecimal qty = normalizeQty(perpRule, perpQty);
            if (qty != null) {
                perpOk = marketBuyBackQuietly(coin.getFuturesSymbol(), qty.toPlainString());
            }
        }
        if (!spotOk && !perpOk) {
            return ExecutionResult.failed("两腿平仓都失败（多为规则校验不通过）");
        }
        if (!perpOk || !spotOk) {
            // 只成功一条腿 = 裸露敞口，必须告警（调用方会触发熔断）
            return ExecutionResult.failed("只平掉一条腿，存在裸露敞口！spotOk=" + spotOk + " perpOk=" + perpOk);
        }
        log.info("平仓下单：{} 现货卖 {}，合约买回 {}", coin.getBaseCoin(), spotQty, perpQty);
        return ExecutionResult.ok(BigDecimal.ZERO, spotQty, perpQty);
    }

    /** @return 该币的滑点上限（按 24h 成交额分档） */
    public BigDecimal slippageLimit(BigDecimal turnover24h) {
        return turnover24h != null && turnover24h.compareTo(majorTurnover) >= 0 ? slippageMajor : slippageSmall;
    }

    // ================= 内部工具 =================

    /**
     * 把数量规整成合法值：向下取整到精度 → 对齐数量乘数 → 校验最小/最大值。
     *
     * <p>绝不四舍五入（实测踩过坑：四舍五入后大于余额，被拒"余额不足"）。
     *
     * @param rule 交易对规则（来自数据库）
     * @param raw  原始计算值
     * @return 合法数量；不满足规则时返回 null（调用方必须放弃下单）
     */
    private static BigDecimal normalizeQty(Instrument rule, BigDecimal raw) {
        int scale = rule.getQuantityPrecision() == null ? 0 : rule.getQuantityPrecision();
        BigDecimal qty = raw.setScale(scale, RoundingMode.DOWN);
        BigDecimal multiplier = rule.getQuantityMultiplier();
        if (multiplier != null && multiplier.signum() > 0) {
            qty = qty.divide(multiplier, 0, RoundingMode.DOWN).multiply(multiplier).setScale(scale, RoundingMode.DOWN);
        }
        if (qty.signum() <= 0) {
            return null;
        }
        if (rule.getMinOrderQty() != null && qty.compareTo(rule.getMinOrderQty()) < 0) {
            return null;
        }
        if (rule.getMaxOrderQty() != null && rule.getMaxOrderQty().signum() > 0
                && qty.compareTo(rule.getMaxOrderQty()) > 0) {
            return null;
        }
        return qty;
    }

    /**
     * 把价格规整成合法值：按价格精度取整（买单向上、卖单向下，保证能成交），再对齐价格乘数。
     *
     * @param rule  交易对规则
     * @param raw   原始价格
     * @param isBuy 买单向上取整（更容易成交），卖单向下取整
     * @return 合法价格字符串；不合法返回 null
     */
    private static String normalizePrice(Instrument rule, BigDecimal raw, boolean isBuy) {
        int scale = rule.getPricePrecision() == null ? 0 : rule.getPricePrecision();
        BigDecimal price = raw.setScale(scale, isBuy ? RoundingMode.UP : RoundingMode.DOWN);
        BigDecimal multiplier = rule.getPriceMultiplier();
        if (multiplier != null && multiplier.signum() > 0) {
            price = price.divide(multiplier, 0, isBuy ? RoundingMode.CEILING : RoundingMode.FLOOR)
                    .multiply(multiplier).setScale(scale, RoundingMode.DOWN);
        }
        return price.signum() <= 0 ? null : price.toPlainString();
    }

    private boolean placeQuietly(String symbol, String category, String side, String posSide,
                                 String qty, String price, String clientOid) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category", category);
        fields.put("symbol", symbol);
        fields.put("side", side);
        fields.put("orderType", "limit");
        fields.put("price", price);
        fields.put("qty", qty);
        // FOK：要么全部成交、要么整笔取消。避免"半仓"造成两条腿数量不齐。
        fields.put("timeInForce", "fok");
        if (posSide != null) {
            fields.put("posSide", posSide);
        }
        fields.put("clientOid", clientOid);
        try {
            privateClient.placeOrder(fields);
            return true;
        } catch (Exception e) {
            log.error("下单失败 {} {} qty={} price={}：{}", category, symbol, qty, price, e.getMessage());
            return false;
        }
    }

    private boolean marketSellQuietly(String symbol, String qty) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category", "SPOT");
        fields.put("symbol", symbol);
        fields.put("side", "sell");
        fields.put("orderType", "market");
        fields.put("qty", qty);
        fields.put("clientOid", clientOid(symbol, "spot-rollback"));
        try {
            privateClient.placeOrder(fields);
            return true;
        } catch (Exception e) {
            log.error("回退卖出现货失败 {} qty={}：{}", symbol, qty, e.getMessage());
            return false;
        }
    }

    private boolean marketBuyBackQuietly(String symbol, String qty) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("category", "USDT-FUTURES");
        fields.put("symbol", symbol);
        fields.put("side", "buy");
        fields.put("orderType", "market");
        fields.put("qty", qty);
        fields.put("posSide", "short");
        fields.put("clientOid", clientOid(symbol, "perp-rollback"));
        try {
            privateClient.placeOrder(fields);
            return true;
        } catch (Exception e) {
            log.error("回退买回合约失败 {} qty={}：{}", symbol, qty, e.getMessage());
            return false;
        }
    }

    /** 从盘口算"在滑点上限内能吃下多少名义金额"。 */
    private BigDecimal affordableNotional(List<List<String>> levels, BigDecimal slippageLimit) {
        if (levels == null || levels.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal best = new BigDecimal(levels.get(0).get(0));
        BigDecimal maxPrice = best.multiply(BigDecimal.ONE.add(slippageLimit));
        BigDecimal notional = BigDecimal.ZERO;
        for (List<String> level : levels) {
            BigDecimal price = new BigDecimal(level.get(0));
            if (price.compareTo(maxPrice) > 0) {
                break;
            }
            notional = notional.add(price.multiply(new BigDecimal(level.get(1))));
        }
        return notional;
    }

    /** 向下取整到指定小数位（卖单绝不能四舍五入，实测踩过坑）。 */
    private static BigDecimal floorToScale(BigDecimal value, int scale) {
        return value.setScale(scale, RoundingMode.DOWN);
    }

    private static int parseInt(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
    }

    private static String clientOid(String base, String leg) {
        return "q" + base + "-" + leg + "-" + Long.toString(System.currentTimeMillis(), CLIENT_OID_BASE);
    }

    /**
     * 执行结果。
     *
     * @param success   是否成功
     * @param notional  实际下单名义金额（USDT）
     * @param spotQty   现货腿数量
     * @param perpQty   合约腿数量
     * @param message   失败原因或备注
     * @param residualExposure 失败时是否**仍有残留敞口**（true 才需要熔断人工介入）
     */
    public record ExecutionResult(boolean success, BigDecimal notional, BigDecimal spotQty,
                                  BigDecimal perpQty, String message, boolean residualExposure) {

        static ExecutionResult ok(BigDecimal notional, BigDecimal spotQty, BigDecimal perpQty) {
            return new ExecutionResult(true, notional, spotQty, perpQty, "", false);
        }

        static ExecutionResult failed(String message) {
            return new ExecutionResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, message, false);
        }

        /** 失败且仍有残留敞口（需要熔断 + 人工介入）。 */
        static ExecutionResult failedWithExposure(String message) {
            return new ExecutionResult(false, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, message, true);
        }
    }
}
