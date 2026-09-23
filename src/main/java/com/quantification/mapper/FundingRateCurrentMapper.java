package com.quantification.mapper;

import com.quantification.entity.FundingRateCurrent;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 实时资金费率（funding_rate_current 表）的数据访问。 */
@Mapper
public interface FundingRateCurrentMapper {

    /**
     * 查询所有币种的最新实时费率（含结算周期，收益计算要用）。
     *
     * @return 每个币种一条
     */
    @Select("""
            SELECT id, symbol, category, funding_rate, funding_rate_interval,
                   next_update_time, min_funding_rate, max_funding_rate, source_time
            FROM funding_rate_current
            ORDER BY symbol
            """)
    List<FundingRateCurrent> findAll();

    /**
     * 覆盖写入某个币种的最新实时费率。
     *
     * <p>唯一键是 {@code symbol}，同一个币只有一行；重复写入时整行更新。
     *
     * @param row 要写入的记录
     * @return 影响行数（新增为 1，更新为 2）
     */
    @Insert("""
            INSERT INTO funding_rate_current
                (symbol, category, funding_rate, funding_rate_interval,
                 next_update_time, min_funding_rate, max_funding_rate, source_time)
            VALUES
                (#{symbol}, #{category}, #{fundingRate}, #{fundingRateInterval},
                 #{nextUpdateTime}, #{minFundingRate}, #{maxFundingRate}, #{sourceTime}) AS new
            ON DUPLICATE KEY UPDATE
                category = new.category,
                funding_rate = new.funding_rate,
                funding_rate_interval = new.funding_rate_interval,
                next_update_time = new.next_update_time,
                min_funding_rate = new.min_funding_rate,
                max_funding_rate = new.max_funding_rate,
                source_time = new.source_time
            """)
    int upsert(FundingRateCurrent row);
}
