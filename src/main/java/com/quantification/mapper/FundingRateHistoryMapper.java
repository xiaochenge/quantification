package com.quantification.mapper;

import com.quantification.entity.FundingRateHistory;
import com.quantification.entity.FundingRateStat;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 历史资金费率（funding_rate_history 表）的数据访问。 */
@Mapper
public interface FundingRateHistoryMapper {

    /**
     * 插入一条历史资金费率。
     *
     * <p>用 {@code INSERT IGNORE}：唯一键是 {@code (symbol, funding_time)}，
     * 同一个结算点重复采集时直接跳过，保证幂等。
     *
     * @param row 要写入的记录
     * @return 实际写入行数：1 表示新插入，0 表示已存在被跳过
     */
    @Insert("""
            INSERT IGNORE INTO funding_rate_history
                (symbol, category, funding_rate, funding_time)
            VALUES
                (#{symbol}, #{category}, #{fundingRate}, #{fundingTime})
            """)
    int insertIgnore(FundingRateHistory row);

    /**
     * 按币种聚合窗口内的资金费率，供收益计算使用。
     *
     * <p>关键点：{@code SUM(funding_rate)} 会把正负费率一起累加（净额口径），
     * 这是"正负都要算"这条规则的落地点。
     *
     * @param from 窗口起始时间（含）
     * @return 每个币种一条统计结果
     */
    @Select("""
            SELECT symbol,
                   COUNT(*)          AS sample_count,
                   SUM(funding_rate) AS rate_sum,
                   MIN(funding_time) AS first_time,
                   MAX(funding_time) AS last_time
            FROM funding_rate_history
            WHERE funding_time >= #{from}
            GROUP BY symbol
            ORDER BY symbol
            """)
    List<FundingRateStat> summarizeSince(@Param("from") LocalDateTime from);

    /**
     * 统计某个币种已入库的历史条数。
     *
     * <p>用途：已采过的币只需拉最新一页（增量），没采过的才需要翻页回补 90 天，
     * 避免每次定时任务都做全量拉取。
     *
     * @param symbol 交易对
     * @return 已入库条数
     */
    @Select("SELECT COUNT(*) FROM funding_rate_history WHERE symbol = #{symbol}")
    int countBySymbol(@Param("symbol") String symbol);
}
