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
     * 按币种聚合<b>全部历史</b>的资金费率（不限时间窗口），用于后台"全历史年化"列。
     *
     * <p>口径与 {@link #summarizeSince} 一致（正负累加），只是不加时间过滤，
     * 让长期表现可以和第 10 天 / 90 天窗口对照着看。
     *
     * @return 每个币种一条统计结果
     */
    @Select("""
            SELECT symbol,
                   COUNT(*)          AS sample_count,
                   SUM(funding_rate) AS rate_sum,
                   MIN(funding_time) AS first_time,
                   MAX(funding_time) AS last_time
            FROM funding_rate_history
            GROUP BY symbol
            ORDER BY symbol
            """)
    List<FundingRateStat> summarizeAll();

    /**
     * 取某个币种已入库的<b>最新结算时间</b>。
     *
     * <p>增量采集用它做"从新到旧翻页、碰到它即停"的锚点：接口按结算时间从新到旧返回，
     * 一旦翻到早于或等于它的记录，说明后面的都是老数据，无需再拉。这样无论暂停多久，
     * 都能把中间断掉的结算点补齐，且不会重复拉旧数据。
     *
     * @param symbol 交易对
     * @return 最新结算时间；库里还没有该币数据时返回 null
     */
    @Select("SELECT MAX(funding_time) FROM funding_rate_history WHERE symbol = #{symbol}")
    LocalDateTime latestFundingTime(@Param("symbol") String symbol);

    /**
     * 取某个币种最近若干笔结算（按时间倒序），用于"恢复轮数"过滤。
     *
     * @param symbol 交易对
     * @param limit  取多少笔
     * @return 最近结算列表（新的在前）
     */
    @Select("""
            SELECT id, symbol, category, funding_rate, funding_time
            FROM funding_rate_history
            WHERE symbol = #{symbol}
            ORDER BY funding_time DESC
            LIMIT #{limit}
            """)
    List<FundingRateHistory> findRecent(@Param("symbol") String symbol, @Param("limit") int limit);

    /**
     * 取某个币在指定时间区间内的结算点（升序），供自建 mock 结算资金费。
     *
     * <p>区间是左开右闭 {@code (from, to]}：{@code from} 用"上一次已入账的结算时间"，
     * 这样同一个结算点不会被结算两次；{@code to} 用当前时间，避免把还没到结算时间的费率提前入账。
     *
     * @param symbol 永续交易对
     * @param from   起始时间（不含）
     * @param to     结束时间（含）
     * @return 结算点列表（时间升序）
     */
    @Select("""
            SELECT id, symbol, category, funding_rate, funding_time
            FROM funding_rate_history
            WHERE symbol = #{symbol} AND funding_time > #{from} AND funding_time <= #{to}
            ORDER BY funding_time
            """)
    List<FundingRateHistory> findSettlementsBetween(@Param("symbol") String symbol,
                                                    @Param("from") LocalDateTime from,
                                                    @Param("to") LocalDateTime to);

    /**
     * 取某个币种全部结算点（按时间升序），供"恢复轮数过滤"在内存里逐笔计算。
     *
     * <p>恢复轮数需要两样：当前"连续为正"的轮数，以及历史"从负费率恢复到正"的平均轮数，
     * 都要按时间顺序逐笔算，SQL 不好写，所以把整条序列取回 Java 侧处理。
     *
     * @param symbol 交易对
     * @return 结算点列表（时间升序）；没有数据时返回空列表
     */
    @Select("""
            SELECT id, symbol, category, funding_rate, funding_time
            FROM funding_rate_history
            WHERE symbol = #{symbol}
            ORDER BY funding_time
            """)
    List<FundingRateHistory> findOrderedBySymbol(@Param("symbol") String symbol);
}
