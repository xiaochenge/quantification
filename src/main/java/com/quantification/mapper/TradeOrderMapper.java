package com.quantification.mapper;

import com.quantification.entity.TradeOrder;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 订单（{@code trade_order} 表）的数据访问。 */
@Mapper
public interface TradeOrderMapper {

    /**
     * 插入一笔订单，并回填自增主键（成交明细要用它关联）。
     *
     * @param row 订单
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO trade_order
                (source, exchange_order_id, client_oid, symbol, category, side, pos_side,
                 order_type, time_in_force, price, qty, cum_exec_qty, avg_price, order_status,
                 reduce_only, fee, fee_coin, reject_reason,
                 exchange_created_time, exchange_updated_time)
            VALUES
                (#{source}, #{exchangeOrderId}, #{clientOid}, #{symbol}, #{category}, #{side}, #{posSide},
                 #{orderType}, #{timeInForce}, #{price}, #{qty}, #{cumExecQty}, #{avgPrice}, #{orderStatus},
                 #{reduceOnly}, #{fee}, #{feeCoin}, #{rejectReason},
                 #{exchangeCreatedTime}, #{exchangeUpdatedTime})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TradeOrder row);

    /**
     * 按 clientOid 查订单（幂等与反查都靠它）。
     *
     * @param clientOid 自定义订单号
     * @return 订单；不存在返回 null
     */
    @Select("""
            SELECT id, source, exchange_order_id, client_oid, symbol, category, side, pos_side,
                   order_type, time_in_force, price, qty, cum_exec_qty, avg_price, order_status,
                   reduce_only, fee, fee_coin, reject_reason, exchange_created_time, exchange_updated_time
            FROM trade_order
            WHERE client_oid = #{clientOid}
            """)
    TradeOrder findByClientOid(@Param("clientOid") String clientOid);

    /**
     * 查最近的订单（后台展示与排查用）。
     *
     * @param source 数据来源（mock / demo / real）
     * @param limit  取多少条
     * @return 订单列表（新的在前）
     */
    @Select("""
            SELECT id, source, exchange_order_id, client_oid, symbol, category, side, pos_side,
                   order_type, time_in_force, price, qty, cum_exec_qty, avg_price, order_status,
                   reduce_only, fee, fee_coin, reject_reason, exchange_created_time, exchange_updated_time
            FROM trade_order
            WHERE source = #{source}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<TradeOrder> findRecent(@Param("source") String source, @Param("limit") int limit);

    /**
     * 统计某个来源的订单数。
     *
     * @param source 数据来源
     * @return 订单条数
     */
    @Select("SELECT COUNT(*) FROM trade_order WHERE source = #{source}")
    int countBySource(@Param("source") String source);

    /**
     * 更新订单状态（撤单、部分成交回填等）。
     *
     * <p>终态（filled / cancelled / rejected）不允许被改回去——所以这里只更新状态字段，
     * 由调用方保证不会把终态改成非终态。
     *
     * @param clientOid   自定义订单号
     * @param orderStatus 新状态
     * @param rejectReason 原因（撤销 / 拒绝原因，可为空）
     * @param cumExecQty  累计成交数量
     * @param avgPrice    成交均价
     * @return 影响行数
     */
    @org.apache.ibatis.annotations.Update("""
            UPDATE trade_order
            SET order_status = #{orderStatus},
                reject_reason = #{rejectReason},
                cum_exec_qty = #{cumExecQty},
                avg_price = #{avgPrice}
            WHERE client_oid = #{clientOid}
            """)
    int updateStatus(@Param("clientOid") String clientOid,
                     @Param("orderStatus") String orderStatus,
                     @Param("rejectReason") String rejectReason,
                     @Param("cumExecQty") BigDecimal cumExecQty,
                     @Param("avgPrice") BigDecimal avgPrice);
}
