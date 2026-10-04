package com.quantification.mapper;

import com.quantification.entity.AccountBalanceSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 账户权益快照（{@code account_balance_snapshot} 表）的数据访问。 */
@Mapper
public interface AccountBalanceSnapshotMapper {

    /**
     * 追加一条账户权益快照。
     *
     * @param row 快照
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO account_balance_snapshot
                (source, account_equity_usd, usdt_equity, unrealised_pnl_usd,
                 eff_equity, mmr, mgn_ratio, position_value, source_time)
            VALUES
                (#{source}, #{accountEquityUsd}, #{usdtEquity}, #{unrealisedPnlUsd},
                 #{effEquity}, #{mmr}, #{mgnRatio}, #{positionValue}, #{sourceTime})
            """)
    int insert(AccountBalanceSnapshot row);

    /**
     * 查某个来源最近的权益快照（画净值曲线 / 算滚动年化）。
     *
     * @param source 数据来源
     * @param limit  取多少条
     * @return 快照列表（新的在前）
     */
    @Select("""
            SELECT id, source, account_equity_usd, usdt_equity, unrealised_pnl_usd,
                   eff_equity, mmr, mgn_ratio, position_value, source_time
            FROM account_balance_snapshot
            WHERE source = #{source}
            ORDER BY source_time DESC
            LIMIT #{limit}
            """)
    List<AccountBalanceSnapshot> findRecent(@Param("source") String source, @Param("limit") int limit);

    /**
     * 取某个时间点之后的第一条快照（算滚动年化的起点，避免窗口内没有精确对齐的点）。
     *
     * @param source 数据来源
     * @param from   起始时间
     * @return 快照；窗口内没有数据返回 null
     */
    @Select("""
            SELECT id, source, account_equity_usd, usdt_equity, unrealised_pnl_usd,
                   eff_equity, mmr, mgn_ratio, position_value, source_time
            FROM account_balance_snapshot
            WHERE source = #{source} AND source_time >= #{from}
            ORDER BY source_time
            LIMIT 1
            """)
    AccountBalanceSnapshot findFirstSince(@Param("source") String source, @Param("from") LocalDateTime from);
}
