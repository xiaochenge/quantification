package com.quantification.mapper;

import com.quantification.entity.TradeFill;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 成交明细（{@code trade_fill} 表）的数据访问。 */
@Mapper
public interface TradeFillMapper {

    /**
     * 插入一条成交明细。
     *
     * <p>用 {@code INSERT IGNORE}：唯一键是 {@code dedup_key}
     * （模拟成交用 {@code mock:<clientOid>}），重复写入会被跳过，保证幂等。
     *
     * @param row 成交明细
     * @return 1 表示新插入，0 表示已存在被跳过
     */
    @Insert("""
            INSERT IGNORE INTO trade_fill
                (source, dedup_key, exec_id, order_id, client_oid, strategy_position_id, leg,
                 symbol, category, side, trade_side, order_type, exec_price, exec_qty, exec_value,
                 trade_scope, fee, fee_coin, slippage, exec_pnl, created_time)
            VALUES
                (#{source}, #{dedupKey}, #{execId}, #{orderId}, #{clientOid}, #{strategyPositionId}, #{leg},
                 #{symbol}, #{category}, #{side}, #{tradeSide}, #{orderType}, #{execPrice}, #{execQty}, #{execValue},
                 #{tradeScope}, #{fee}, #{feeCoin}, #{slippage}, #{execPnl}, #{createdTime})
            """)
    int insertIgnore(TradeFill row);

    /**
     * 统计某个来源的成交笔数。
     *
     * @param source 数据来源
     * @return 成交笔数
     */
    @Select("SELECT COUNT(*) FROM trade_fill WHERE source = #{source}")
    int countBySource(@Param("source") String source);

    /**
     * 汇总某个来源的手续费支出（成本核算用）。
     *
     * @param source 数据来源
     * @return 手续费合计（计价币，USDT）
     */
    @Select("SELECT COALESCE(SUM(fee), 0) FROM trade_fill WHERE source = #{source}")
    BigDecimal sumFee(@Param("source") String source);

    /**
     * 查最近的成交明细（后台展示与排查用）。
     *
     * @param source 数据来源
     * @param limit  取多少条
     * @return 成交列表（新的在前）
     */
    @Select("""
            SELECT id, source, dedup_key, exec_id, order_id, client_oid, strategy_position_id, leg,
                   symbol, category, side, trade_side, order_type, exec_price, exec_qty, exec_value,
                   trade_scope, fee, fee_coin, slippage, exec_pnl, created_time
            FROM trade_fill
            WHERE source = #{source}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<TradeFill> findRecent(@Param("source") String source, @Param("limit") int limit);

    /**
     * 按币种汇总某个来源累计手续费（供持仓页 / 成本页展示"该币花了多少手续费"）。
     *
     * @param source 数据来源
     * @return 每行 {symbol, total}
     */
    @Select("""
            SELECT symbol, COALESCE(SUM(fee), 0) AS total
            FROM trade_fill
            WHERE source = #{source}
            GROUP BY symbol
            """)
    List<Map<String, Object>> sumFeeBySymbol(@Param("source") String source);

    /**
     * 汇总某个来源、某条腿（spot/perp）的已实现盈亏（平仓腿才有 exec_pnl）。
     *
     * @param source 数据来源
     * @param leg    腿：spot / perp
     * @return 已实现盈亏合计（USDT，正=赚，负=亏）
     */
    @Select("SELECT COALESCE(SUM(exec_pnl), 0) FROM trade_fill WHERE source = #{source} AND leg = #{leg}")
    BigDecimal sumExecPnl(@Param("source") String source, @Param("leg") String leg);
}
