package com.quantification.mapper;

import com.quantification.entity.MockPosition;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 自建 mock 持仓（{@code mock_position} 表）的数据访问。 */
@Mapper
public interface MockPositionMapper {

    /**
     * 读取模拟交易所的全部持仓。
     *
     * <p>程序启动时用它把上一轮的模拟仓位恢复出来（与实盘"启动对账"同一个目的）。
     *
     * @return 每个币一行；没有持仓时返回空列表
     */
    @Select("""
            SELECT base_coin, spot_symbol, futures_symbol, spot_qty, perp_qty,
                   spot_avg_price, perp_avg_price, opened_at, updated_at
            FROM mock_position
            ORDER BY base_coin
            """)
    List<MockPosition> findAll();

    /**
     * 新增或更新一个币的模拟持仓（按 base_coin 唯一）。
     *
     * @param row 持仓
     * @return 影响行数（新增 1，更新 2）
     */
    @Insert("""
            INSERT INTO mock_position
                (base_coin, spot_symbol, futures_symbol, spot_qty, spot_avg_price, perp_qty, perp_avg_price, opened_at)
            VALUES
                (#{baseCoin}, #{spotSymbol}, #{futuresSymbol}, #{spotQty}, #{spotAvgPrice}, #{perpQty}, #{perpAvgPrice}, #{openedAt}) AS new
            ON DUPLICATE KEY UPDATE
                spot_symbol = new.spot_symbol,
                futures_symbol = new.futures_symbol,
                spot_qty = new.spot_qty,
                spot_avg_price = new.spot_avg_price,
                perp_qty = new.perp_qty,
                perp_avg_price = new.perp_avg_price,
                opened_at = new.opened_at
            """)
    int upsert(MockPosition row);

    /**
     * 删除一个币的模拟持仓（两条腿都平掉、且数量确实归零时才调用）。
     *
     * @param baseCoin 标的币种
     * @return 影响行数
     */
    @Delete("DELETE FROM mock_position WHERE base_coin = #{baseCoin}")
    int delete(@Param("baseCoin") String baseCoin);
}
