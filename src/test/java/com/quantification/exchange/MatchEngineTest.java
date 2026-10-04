package com.quantification.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MatchEngine} 的单元测试。
 *
 * <p>为什么这些用例重要：自建 mock 的"收益是不是可信"，一半取决于撮合是不是按真实盘口算的。
 * 这里用构造的盘口验证三件事——FOK 吃满才成交、吃不满整笔撤销、市价单允许部分成交且滑点
 * 随深度自然放大。
 */
class MatchEngineTest {

    /** 一个简单的卖档（价格升序）：0.5 个 @100、1 个 @101、2 个 @105。 */
    private static final List<List<String>> ASKS = List.of(
            List.of("100", "0.5"),
            List.of("101", "1"),
            List.of("105", "2"));

    /** 一个简单的买档（价格降序）：0.5 个 @100、1 个 @99。 */
    private static final List<List<String>> BIDS = List.of(
            List.of("100", "0.5"),
            List.of("99", "1"));

    @Test
    @DisplayName("限价 FOK 全部成交：成交均价是逐档加权，滑点按最优价算")
    void limitFokFillsCompletely() {
        // 买 1.5 个：0.5@100 + 1@101 → 金额 50 + 101 = 151，均价 100.666666...
        MatchEngine.Match match = MatchEngine.matchLimitFok(ASKS, new BigDecimal("1.5"),
                new BigDecimal("101"), true);

        assertTrue(match.isFilled());
        assertEquals(0, match.filledQty().compareTo(new BigDecimal("1.5")));
        assertEquals(0, match.filledNotional().compareTo(new BigDecimal("151")));
        assertEquals(0, match.avgPrice().compareTo(new BigDecimal("100.666666666667")));
        // 滑点 = |100.6666.../100 - 1| = 0.00666666
        assertEquals(0, match.slippage().compareTo(new BigDecimal("0.00666667")));
    }

    @Test
    @DisplayName("限价 FOK 吃不满：整笔撤销，成交量为 0（不能留下半仓）")
    void limitFokCancelsWhenDepthInsufficient() {
        // 限价 101 内只有 1.5 个可成交，却要买 2 个 → FOK 撤销
        MatchEngine.Match match = MatchEngine.matchLimitFok(ASKS, new BigDecimal("2"),
                new BigDecimal("101"), true);

        assertFalse(match.isFilled());
        assertEquals(0, match.filledQty().compareTo(BigDecimal.ZERO));
        assertTrue(match.reason().contains("FOK"));
    }

    @Test
    @DisplayName("限价 FOK 只吃最优档：滑点为 0")
    void limitFokAtBestPriceHasNoSlippage() {
        MatchEngine.Match match = MatchEngine.matchLimitFok(ASKS, new BigDecimal("0.5"),
                new BigDecimal("100"), true);

        assertTrue(match.isFilled());
        assertEquals(0, match.avgPrice().compareTo(new BigDecimal("100")));
        assertEquals(0, match.slippage().compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("市价卖出：逐档吃买档，深度不足时部分成交并给出原因")
    void marketOrderAllowsPartialFill() {
        // 卖 2 个，但买档只有 1.5 个 → 部分成交 1.5，均价 = (50 + 99) / 1.5 = 99.333...
        MatchEngine.Match match = MatchEngine.matchMarket(BIDS, new BigDecimal("2"), false, 100);

        assertTrue(match.isFilled());
        assertEquals(0, match.filledQty().compareTo(new BigDecimal("1.5")));
        assertEquals(0, match.avgPrice().compareTo(new BigDecimal("99.333333333333")));
        assertTrue(match.reason().contains("部分成交"));
    }

    @Test
    @DisplayName("空盘口：不成交，也不能抛异常")
    void emptyBookDoesNotFill() {
        MatchEngine.Match match = MatchEngine.matchMarket(List.of(), new BigDecimal("1"), true, 100);

        assertFalse(match.isFilled());
        assertEquals(0, match.filledQty().compareTo(BigDecimal.ZERO));
    }
}
