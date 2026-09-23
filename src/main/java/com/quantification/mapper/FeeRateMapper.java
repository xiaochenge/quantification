package com.quantification.mapper;

import com.quantification.entity.FeeRate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** 手续费率快照（fee_rate 表）的数据访问。 */
@Mapper
public interface FeeRateMapper {

    /**
     * 追加一条费率快照。
     *
     * @param row 要写入的记录
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO fee_rate
                (symbol, category, maker_fee_rate, taker_fee_rate, rpi_flag, source_time)
            VALUES
                (#{symbol}, #{category}, #{makerFeeRate}, #{takerFeeRate}, #{rpiFlag}, #{sourceTime})
            """)
    int insert(FeeRate row);
}
