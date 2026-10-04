package com.quantification.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 盘口撮合引擎（自建 mock 的核心算法）。
 *
 * <p>做法与 {@code scripts/orderbook-slippage.py} 一致：从最优价开始逐档吃真实订单簿，
 * 累加"能成交多少量、加权均价是多少"。这样得到的成交价就是<b>真实盘口能撮出来的价</b>，
 * 而不是拍一个固定滑点值——小币在薄盘口上的滑点会自然放大，大盘币几乎为零。
 *
 * <p>两种情况：
 * <ul>
 *   <li>{@link #matchLimitFok}：限价 FOK 单。限价内吃得下全部数量才成交，否则<b>整笔撤销</b>
 *       （与实盘一致：FOK 要么全成、要么全不成，不会留下半仓）；</li>
 *   <li>{@link #matchMarket}：市价单。逐档吃到数量满足或深度用尽，允许部分成交
 *       （回退腿用的就是市价单）。</li>
 * </ul>
 *
 * <p>本类只做纯计算，不碰网络与数据库，便于单元测试。
 */
public final class MatchEngine {

    /** 成交均价的保留小数位（入库是 DECIMAL(38,18)，12 位足够且日志好读）。 */
    private static final int AVG_PRICE_SCALE = 12;

    private MatchEngine() {
    }

    /**
     * 撮合结果。
     *
     * @param filledQty      成交数量（基础币，0 表示没成交）
     * @param filledNotional 成交金额（计价币）
     * @param avgPrice       成交均价（未成交时为 null）
     * @param slippage       滑点：成交均价相对盘口最优价的不利偏移（小数，未成交时为 null）
     * @param reason         未成交 / 部分成交的原因（便于排查）
     */
    public record Match(BigDecimal filledQty, BigDecimal filledNotional, BigDecimal avgPrice,
                        BigDecimal slippage, String reason) {

        /** @return 是否成交（部分成交也算成交） */
        public boolean isFilled() {
            return filledQty != null && filledQty.signum() > 0;
        }
    }

    /**
     * 撮合限价 FOK 单：限价内能吃下全部数量才成交，否则整笔撤销。
     *
     * @param levels     盘口档位（买单传卖档 asks、卖单传买档 bids），每项 [价格, 数量]
     * @param qty        委托数量（基础币）
     * @param limitPrice 限价（买单是最高可接受价，卖单是最低可接受价）
     * @param isBuy      true = 买单（吃卖档、价格升序）
     * @return 撮合结果；未吃满时返回成交量为 0 的结果（即 FOK 已撤销）
     */
    public static Match matchLimitFok(List<List<String>> levels, BigDecimal qty,
                                      BigDecimal limitPrice, boolean isBuy) {
        Accumulator acc = walk(levels, qty, isBuy ? limitPrice : null, isBuy ? null : limitPrice,
                levels == null ? 0 : levels.size());
        if (acc.filledQty.compareTo(qty) < 0) {
            return new Match(BigDecimal.ZERO, BigDecimal.ZERO, null, null,
                    "限价内可成交量不足（需 " + qty.toPlainString() + "，可成交 "
                            + acc.filledQty.toPlainString() + "），FOK 整笔撤销");
        }
        return toMatch(acc, levels, qty);
    }

    /**
     * 撮合市价单：逐档吃到数量满足或用尽深度，允许部分成交。
     *
     * @param levels    盘口档位（买单传卖档、卖单传买档）
     * @param qty       委托数量（基础币）
     * @param isBuy     true = 买单
     * @param maxLevels 最多吃多少档（防止一次扫穿整个盘口）
     * @return 撮合结果
     */
    public static Match matchMarket(List<List<String>> levels, BigDecimal qty, boolean isBuy, int maxLevels) {
        Accumulator acc = walk(levels, qty, null, null, maxLevels);
        if (acc.filledQty.signum() <= 0) {
            return new Match(BigDecimal.ZERO, BigDecimal.ZERO, null, null, "盘口没有可成交的量");
        }
        return toMatch(acc, levels, qty);
    }

    /** 逐档吃单的累加器。 */
    private static final class Accumulator {

        /** 已成交量（基础币）。 */
        private BigDecimal filledQty = BigDecimal.ZERO;

        /** 已成交金额（计价币）。 */
        private BigDecimal filledNotional = BigDecimal.ZERO;
    }

    /**
     * 逐档吃单。
     *
     * @param levels     盘口档位
     * @param qty        目标数量
     * @param maxPrice   买单限价（null 表示不限）
     * @param minPrice   卖单限价（null 表示不限）
     * @param maxLevels  最多吃多少档
     * @return 累加结果
     */
    private static Accumulator walk(List<List<String>> levels, BigDecimal qty, BigDecimal maxPrice,
                                    BigDecimal minPrice, int maxLevels) {
        Accumulator acc = new Accumulator();
        if (levels == null || levels.isEmpty() || maxLevels <= 0) {
            return acc;
        }
        BigDecimal remaining = qty;
        int used = 0;
        for (List<String> level : levels) {
            if (remaining.signum() <= 0 || used >= maxLevels) {
                break;
            }
            if (level == null || level.size() < 2) {
                continue;
            }
            BigDecimal price = new BigDecimal(level.get(0));
            BigDecimal size = new BigDecimal(level.get(1));
            // 超出限价的档位吃不到，直接停（盘口按价格有序，后面的只会更差）
            if (maxPrice != null && price.compareTo(maxPrice) > 0) {
                break;
            }
            if (minPrice != null && price.compareTo(minPrice) < 0) {
                break;
            }
            BigDecimal take = remaining.min(size);
            acc.filledQty = acc.filledQty.add(take);
            acc.filledNotional = acc.filledNotional.add(take.multiply(price));
            remaining = remaining.subtract(take);
            used++;
        }
        return acc;
    }

    /** 把累加结果换算成成交均价、滑点与说明。 */
    private static Match toMatch(Accumulator acc, List<List<String>> levels, BigDecimal qty) {
        BigDecimal avgPrice = acc.filledNotional.divide(acc.filledQty, AVG_PRICE_SCALE, RoundingMode.HALF_UP);
        BigDecimal best = levels == null || levels.isEmpty() ? null : new BigDecimal(levels.get(0).get(0));
        BigDecimal slippage = best == null || best.signum() <= 0
                ? BigDecimal.ZERO
                : avgPrice.subtract(best).abs().divide(best, 8, RoundingMode.HALF_UP);
        String reason = acc.filledQty.compareTo(qty) < 0
                ? "深度不足，部分成交（委托 " + qty.toPlainString() + "，成交 " + acc.filledQty.toPlainString() + "）"
                : "";
        return new Match(acc.filledQty, acc.filledNotional, avgPrice, slippage, reason);
    }
}
