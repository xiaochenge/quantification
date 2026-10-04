package com.quantification.mapper;

import com.quantification.entity.MockAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 自建 mock 账户现金（{@code mock_account} 表，单行）的数据访问。
 *
 * <p>表里永远只有 {@code account_key = 'mock'} 这一行：模拟交易所的现金余额。
 * 每次买卖 / 收手续费 / 收资金费之后都会覆盖写，保证重启后接着跑。
 */
@Mapper
public interface MockAccountMapper {

    /**
     * 读取模拟账户现金。
     *
     * @param accountKey 账户标识（固定 mock）
     * @return 账户现金记录；第一次运行（还没初始化）返回 null
     */
    @Select("""
            SELECT account_key, usdt_balance, initial_usdt, created_at, updated_at
            FROM mock_account
            WHERE account_key = #{accountKey}
            """)
    MockAccount find(@Param("accountKey") String accountKey);

    /**
     * 初始化或更新模拟账户现金。
     *
     * <p>已存在时只更新余额，<b>不覆盖 initial_usdt</b>——初始资金是收益率的分母，
     * 一旦定下来就不该因为改配置而变动。
     *
     * @param row 账户记录
     * @return 影响行数（新增 1，更新 2）
     */
    @Insert("""
            INSERT INTO mock_account (account_key, usdt_balance, initial_usdt)
            VALUES (#{accountKey}, #{usdtBalance}, #{initialUsdt}) AS new
            ON DUPLICATE KEY UPDATE
                usdt_balance = new.usdt_balance
            """)
    int upsert(MockAccount row);
}
