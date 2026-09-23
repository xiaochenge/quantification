package com.quantification.mapper;

import com.quantification.entity.Instrument;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 交易对规则（instrument 表）的数据访问。 */
@Mapper
public interface InstrumentMapper {

    /**
     * 写入或更新交易对规则（按 category + symbol 唯一）。
     *
     * @param row 规则
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO instrument
                (category, symbol, base_coin, quote_coin, contract_type,
                 min_order_qty, max_order_qty, price_precision, quantity_precision, quote_precision,
                 price_multiplier, quantity_multiplier, maker_fee_rate, taker_fee_rate, source_time)
            VALUES
                (#{category}, #{symbol}, #{baseCoin}, #{quoteCoin}, #{contractType},
                 #{minOrderQty}, #{maxOrderQty}, #{pricePrecision}, #{quantityPrecision}, #{quotePrecision},
                 #{priceMultiplier}, #{quantityMultiplier}, NULL, NULL, NOW()) AS new
            ON DUPLICATE KEY UPDATE
                base_coin = new.base_coin,
                quote_coin = new.quote_coin,
                contract_type = new.contract_type,
                min_order_qty = new.min_order_qty,
                max_order_qty = new.max_order_qty,
                price_precision = new.price_precision,
                quantity_precision = new.quantity_precision,
                quote_precision = new.quote_precision,
                price_multiplier = new.price_multiplier,
                quantity_multiplier = new.quantity_multiplier,
                source_time = new.source_time
            """)
    int upsert(Instrument row);

    /**
     * 按下单需要的两个维度取规则（下单前必查）。
     *
     * @param category 产品线
     * @param symbol   交易对
     * @return 规则；库里没有则返回 null（调用方必须视为"不可下单"）
     */
    @Select("""
            SELECT id, category, symbol, base_coin, quote_coin, contract_type,
                   min_order_qty, max_order_qty, price_precision, quantity_precision, quote_precision,
                   price_multiplier, quantity_multiplier
            FROM instrument
            WHERE category = #{category} AND symbol = #{symbol}
            """)
    Instrument find(@Param("category") String category, @Param("symbol") String symbol);
}
