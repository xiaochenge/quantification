package com.quantification.mapper;

import com.quantification.entity.FundingIncome;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 资金费入账（{@code funding_income} 表）的数据访问。 */
@Mapper
public interface FundingIncomeMapper {

    /**
     * 插入一条资金费入账。
     *
     * <p>用 {@code INSERT IGNORE}：唯一键是 {@code (source, symbol, settlement_time)}。
     * <b>调用方必须按返回值决定是否真的加钱</b>——返回 0 表示这个结算点已经入过账，
     * 重复加钱会让模拟收益失真。
     *
     * @param row 资金费记录
     * @return 1 表示新插入，0 表示该结算点已入账
     */
    @Insert("""
            INSERT IGNORE INTO funding_income
                (source, strategy_position_id, symbol, category, funding_rate,
                 position_qty, mark_price, funding_amount, coin, settlement_time)
            VALUES
                (#{source}, #{strategyPositionId}, #{symbol}, #{category}, #{fundingRate},
                 #{positionQty}, #{markPrice}, #{fundingAmount}, #{coin}, #{settlementTime})
            """)
    int insertIgnore(FundingIncome row);

    /**
     * 查某个币最后一次已入账的结算时间（增量结算的起点）。
     *
     * @param source 数据来源
     * @param symbol 永续交易对
     * @return 最后结算时间；从未入账返回 null
     */
    @Select("""
            SELECT MAX(settlement_time) FROM funding_income
            WHERE source = #{source} AND symbol = #{symbol}
            """)
    LocalDateTime findLastSettlementTime(@Param("source") String source,
                                         @Param("symbol") String symbol);

    /**
     * 汇总某个来源累计收到的资金费（正数=净收到）。
     *
     * @param source 数据来源
     * @return 资金费合计（USDT）
     */
    @Select("SELECT COALESCE(SUM(funding_amount), 0) FROM funding_income WHERE source = #{source}")
    BigDecimal sumAmount(@Param("source") String source);

    /**
     * 查最近若干笔资金费入账。
     *
     * @param source 数据来源
     * @param limit  取多少条
     * @return 资金费列表（新的在前）
     */
    @Select("""
            SELECT id, source, strategy_position_id, symbol, category, funding_rate,
                   position_qty, mark_price, funding_amount, coin, settlement_time
            FROM funding_income
            WHERE source = #{source}
            ORDER BY settlement_time DESC
            LIMIT #{limit}
            """)
    List<FundingIncome> findRecent(@Param("source") String source, @Param("limit") int limit);

    /**
     * 按币种汇总某个来源累计收到的资金费（供持仓页展示"该币赚了多少资金费"）。
     *
     * @param source 数据来源
     * @return 每行 {symbol, total}
     */
    @Select("""
            SELECT symbol, COALESCE(SUM(funding_amount), 0) AS total
            FROM funding_income
            WHERE source = #{source}
            GROUP BY symbol
            """)
    List<Map<String, Object>> sumBySymbol(@Param("source") String source);
}
