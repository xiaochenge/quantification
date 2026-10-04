package com.quantification.mapper;

import com.quantification.entity.WatchCoin;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
            SELECT id, base_coin, spot_symbol, futures_symbol, futures_category, discount_rate,
                   real_supported, demo_supported, enabled, note
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
                (base_coin, spot_symbol, futures_symbol, futures_category, discount_rate,
                 real_supported, demo_supported, enabled, note)
            VALUES
                (#{baseCoin}, #{spotSymbol}, #{futuresSymbol}, #{futuresCategory}, #{discountRate},
                 #{realSupported}, #{demoSupported}, 1, #{note}) AS new
            ON DUPLICATE KEY UPDATE
                spot_symbol = new.spot_symbol,
                futures_symbol = new.futures_symbol,
                futures_category = new.futures_category,
                discount_rate = new.discount_rate,
                real_supported = new.real_supported,
                demo_supported = new.demo_supported,
                enabled = 1
            """)
    int upsert(WatchCoin row);

    /**
     * 查询"当前运行模式下可交易"的币种。
     *
     * <p>模拟盘与实盘的支持范围不同（模拟盘只覆盖少数币），所以按模式过滤，
     * 避免在模拟盘里对不支持的币下单。
     *
     * @param paperTrading true = 模拟盘（取 demo_supported），false = 实盘（取 real_supported）
     * @return 该模式下可交易的币种列表
     */
    @Select("""
            <script>
            SELECT id, base_coin, spot_symbol, futures_symbol, futures_category, discount_rate,
                   real_supported, demo_supported, enabled, note
            FROM watch_coin
            WHERE enabled = 1
              AND <choose><when test="paperTrading">demo_supported</when><otherwise>real_supported</otherwise></choose> = 1
            ORDER BY id
            </script>
            """)
    List<WatchCoin> findTradable(@Param("paperTrading") boolean paperTrading);

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

    /**
     * 按币种查篮子记录（不限启用状态）。
     *
     * <p>自建 mock 用它取保证金折扣率：模拟持仓里可能有刚被停用的币，
     * 折扣率仍要能查到，否则有效权益会算成 0。
     *
     * @param baseCoin 标的币种，如 BTC
     * @return 篮子记录；不存在返回 null
     */
    @Select("""
            SELECT id, base_coin, spot_symbol, futures_symbol, futures_category, discount_rate,
                   real_supported, demo_supported, enabled, note
            FROM watch_coin
            WHERE base_coin = #{baseCoin}
            """)
    WatchCoin findByBaseCoin(@Param("baseCoin") String baseCoin);
}
