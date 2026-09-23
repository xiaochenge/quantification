package com.quantification.mapper;

import com.quantification.entity.WatchCoin;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 监控篮子（watch_coin 表）的数据访问。 */
@Mapper
public interface WatchCoinMapper {

    /**
     * 查询所有启用中的币种，按加入顺序返回。
     *
     * @return 启用中的监控币种列表；没有则返回空列表
     */
    @Select("""
            SELECT id, base_coin, spot_symbol, futures_symbol, futures_category, enabled, note
            FROM watch_coin
            WHERE enabled = 1
            ORDER BY id
            """)
    List<WatchCoin> findEnabled();

    /**
     * 新增或重新启用一个监控币种（按 base_coin 唯一）。
     *
     * <p>已存在时把它重新置为启用，但不覆盖原有备注（备注是人工维护的）。
     *
     * @param row 要写入的币种
     * @return 影响行数（新增为 1，更新为 2）
     */
    @Insert("""
            INSERT INTO watch_coin
                (base_coin, spot_symbol, futures_symbol, futures_category, enabled, note)
            VALUES
                (#{baseCoin}, #{spotSymbol}, #{futuresSymbol}, #{futuresCategory}, 1, #{note}) AS new
            ON DUPLICATE KEY UPDATE
                spot_symbol = new.spot_symbol,
                futures_symbol = new.futures_symbol,
                futures_category = new.futures_category,
                enabled = 1
            """)
    int upsert(WatchCoin row);

    /**
     * 把所有币种置为停用。
     *
     * <p>同步篮子时先全部停用，再把符合条件的重新启用，
     * 这样不达标的币会自动退出篮子，不会残留。
     *
     * @return 影响行数
     */
    @Update("UPDATE watch_coin SET enabled = 0")
    int disableAll();
}
