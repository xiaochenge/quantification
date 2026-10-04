-- ============================================================
-- 模块 9（自建 mock）落地所需要的表。
--
-- 背景：V1 只建了 10 张"数据层"表，交易 / 策略 / 模拟相关的表一直没建，
-- 于是模拟成交无处落库（只能靠"反查交易所状态"做幂等）。
--
-- 本脚本一次建 6 张表：
--   * trade_order      —— 订单（模拟与实盘共用，用 source 区分）
--   * trade_fill       —— 成交明细（模拟与实盘共用，用 source 区分）
--   * funding_income   —— 资金费逐笔入账（mock 由我们自己按真实费率算）
--   * mock_account     —— 自建 mock 的"交易所账户"现金（单行表）
--   * mock_position    —— 自建 mock 的持仓（现货 + 永续两条腿，每币一行）
--   * strategy_position —— 策略视角的对冲组合（模块 8 落地后写入；本脚本先建好，
--                          这样 trade_fill / funding_income 里的 strategy_position_id 有归属）
--
-- 通用约定同 V1：时间用 DATETIME（Asia/Shanghai），金额 / 数量用 DECIMAL(38,18)，
-- 每个字段都写 COMMENT。
-- ============================================================

-- ------------------------------------------------------------
-- 1. 订单：本地订单账本 + 状态机
-- ------------------------------------------------------------
CREATE TABLE trade_order (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    source                VARCHAR(16)     NOT NULL DEFAULT 'real' COMMENT '数据来源：mock 自建模拟 / demo 官方模拟盘 / real 实盘',
    exchange_order_id     VARCHAR(64)     NULL COMMENT '交易所订单号（mock 单是本地生成的假号）',
    client_oid            VARCHAR(64)     NOT NULL COMMENT '自定义订单号（幂等键：同一个 clientOid 只允许有一条订单）',
    symbol                VARCHAR(64)     NOT NULL COMMENT '交易对，如 BTCUSDT',
    category              VARCHAR(32)     NOT NULL COMMENT '产品线：SPOT / USDT-FUTURES',
    side                  VARCHAR(8)      NOT NULL COMMENT '买卖方向：buy / sell',
    pos_side              VARCHAR(16)     NULL COMMENT '持仓方向：short 空 / long 多；现货单为空',
    order_type            VARCHAR(16)     NOT NULL COMMENT '订单类型：limit 限价 / market 市价',
    time_in_force         VARCHAR(8)      NULL COMMENT '有效方式：fok / ioc / gtc；市价单为空',
    price                 DECIMAL(38,18)  NULL COMMENT '委托价（市价单为空）',
    qty                   DECIMAL(38,18)  NOT NULL COMMENT '委托数量（基础币）',
    cum_exec_qty          DECIMAL(38,18)  NOT NULL DEFAULT 0 COMMENT '累计成交数量（基础币），反查订单时以它为准',
    avg_price             DECIMAL(38,18)  NULL COMMENT '成交均价（基础币计价）',
    order_status          VARCHAR(24)     NOT NULL COMMENT '状态：live 已受理 / partially_filled 部成 / filled 全成 / cancelled 已撤 / rejected 被拒',
    reduce_only           TINYINT         NOT NULL DEFAULT 0 COMMENT '是否只减仓：1 是 / 0 否',
    fee                   DECIMAL(38,18)  NULL COMMENT '本单手续费合计（计价币，现货与合约都是 USDT）',
    fee_coin              VARCHAR(16)     NULL COMMENT '手续费币种',
    reject_reason         VARCHAR(255)    NULL COMMENT '被拒 / 被撤原因（便于事后排查是哪条规则拒的）',
    exchange_created_time DATETIME        NULL COMMENT '交易所创建时间',
    exchange_updated_time DATETIME        NULL COMMENT '交易所更新时间',
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本地更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_order_client_oid (client_oid),
    KEY idx_trade_order_symbol_time (symbol, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单（模拟与实盘共用）';

-- ------------------------------------------------------------
-- 2. 成交明细：成本与盈亏的原始依据
-- ------------------------------------------------------------
CREATE TABLE trade_fill (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    source               VARCHAR(16)     NOT NULL DEFAULT 'real' COMMENT '数据来源：mock / demo / real',
    dedup_key            VARCHAR(96)     NOT NULL COMMENT '去重键：真实成交 real:<execId>；模拟成交 mock:<clientOid>',
    exec_id              VARCHAR(64)     NULL COMMENT '交易所成交 ID（模拟成交为空）',
    order_id             BIGINT UNSIGNED NULL COMMENT '关联 trade_order.id',
    client_oid           VARCHAR(64)     NULL COMMENT '下单时的自定义订单号',
    strategy_position_id BIGINT UNSIGNED NULL COMMENT '关联 strategy_position.id（模块 8 落地后回填）',
    leg                  VARCHAR(8)      NULL COMMENT '腿：spot 现货 / perp 永续',
    symbol               VARCHAR(64)     NOT NULL COMMENT '交易对',
    category             VARCHAR(32)     NOT NULL COMMENT '产品线',
    side                 VARCHAR(8)      NOT NULL COMMENT '买卖方向：buy / sell',
    trade_side           VARCHAR(8)      NULL COMMENT '开平方向：open 开仓 / close 平仓',
    order_type           VARCHAR(16)     NULL COMMENT '订单类型：limit / market',
    exec_price           DECIMAL(38,18)  NOT NULL COMMENT '成交均价',
    exec_qty             DECIMAL(38,18)  NOT NULL COMMENT '成交数量（基础币）',
    exec_value           DECIMAL(38,18)  NOT NULL COMMENT '成交金额（计价币，= 成交均价 × 数量）',
    trade_scope          VARCHAR(8)      NULL COMMENT '吃单 / 挂单：taker / maker（决定费率）',
    fee                  DECIMAL(38,18)  NULL COMMENT '手续费（计价币，正数表示支出）',
    fee_coin             VARCHAR(16)     NULL COMMENT '手续费币种',
    slippage             DECIMAL(38,18)  NULL COMMENT '滑点：成交均价相对下单前盘口最优价的偏移（小数，模拟才有）',
    exec_pnl             DECIMAL(38,18)  NULL COMMENT '本笔已实现盈亏（平仓腿才有，模块 8 用）',
    created_time         DATETIME        NULL COMMENT '成交时间',
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_trade_fill_dedup (dedup_key),
    KEY idx_trade_fill_symbol_time (symbol, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成交明细（模拟与实盘共用，用 source 区分）';

-- ------------------------------------------------------------
-- 3. 资金费入账：策略的核心收益来源
-- ------------------------------------------------------------
CREATE TABLE funding_income (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    source               VARCHAR(16)     NOT NULL DEFAULT 'mock' COMMENT '数据来源：mock 自算 / real 交易所流水核对',
    strategy_position_id BIGINT UNSIGNED NULL COMMENT '关联 strategy_position.id（模块 8 落地后回填）',
    symbol               VARCHAR(64)     NOT NULL COMMENT '永续交易对',
    category             VARCHAR(32)     NULL COMMENT '产品线',
    funding_rate         DECIMAL(38,18)  NOT NULL COMMENT '本次结算的资金费率（小数，0.0001 表示 0.01%）',
    position_qty         DECIMAL(38,18)  NULL COMMENT '结算时的持仓数量（基础币，空头为正数）',
    mark_price           DECIMAL(38,18)  NULL COMMENT '结算时用的最新价（名义价值 = 数量 × 该价格）',
    funding_amount       DECIMAL(38,18)  NOT NULL COMMENT '本次入账金额（USDT，正数=收到，负数=付出）',
    coin                 VARCHAR(16)     NULL COMMENT '结算币种，通常为 USDT',
    settlement_time      DATETIME        NOT NULL COMMENT '结算时间（去重键的一部分，一个结算点只入账一次）',
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_funding_income_point (source, symbol, settlement_time),
    KEY idx_funding_income_time (settlement_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资金费入账明细';

-- ------------------------------------------------------------
-- 4. 自建 mock 的账户现金（单行表）
-- ------------------------------------------------------------
CREATE TABLE mock_account (
    account_key   VARCHAR(32)    NOT NULL COMMENT '账户标识，固定为 mock（保证只有一行）',
    usdt_balance  DECIMAL(38,18) NOT NULL COMMENT 'USDT 现金余额（含手续费与资金费的收支）',
    initial_usdt  DECIMAL(38,18) NOT NULL COMMENT '模拟账户初始 USDT 资金（用于算收益率）',
    created_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    updated_at    DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本地更新时间',
    PRIMARY KEY (account_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='自建 mock 的交易所账户现金（单行）';

-- ------------------------------------------------------------
-- 5. 自建 mock 的持仓（模拟交易所视角，每币一行）
-- ------------------------------------------------------------
CREATE TABLE mock_position (
    base_coin       VARCHAR(32)    NOT NULL COMMENT '标的币种，如 BTC',
    spot_symbol     VARCHAR(64)    NOT NULL COMMENT '现货交易对',
    futures_symbol  VARCHAR(64)    NOT NULL COMMENT '永续交易对',
    spot_qty        DECIMAL(38,18) NOT NULL DEFAULT 0 COMMENT '现货多头数量（基础币）',
    perp_qty        DECIMAL(38,18) NOT NULL DEFAULT 0 COMMENT '永续空头数量（基础币，正数表示空头）',
    perp_avg_price  DECIMAL(38,18) NULL COMMENT '永续空头开仓均价',
    opened_at       DATETIME       NULL COMMENT '本币当前这组仓位最早的开仓时间（资金费只结算该时间之后的结算点）',
    updated_at      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本地更新时间',
    PRIMARY KEY (base_coin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='自建 mock 的持仓（模拟交易所视角）';

-- ------------------------------------------------------------
-- 6. 策略仓位：一组"现货多 + 永续空"的对冲组合（业务视角）
-- ------------------------------------------------------------
CREATE TABLE strategy_position (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    base_coin            VARCHAR(32)     NOT NULL COMMENT '标的币种',
    spot_symbol          VARCHAR(64)     NOT NULL COMMENT '现货交易对',
    futures_symbol       VARCHAR(64)     NOT NULL COMMENT '永续交易对',
    status               VARCHAR(16)     NOT NULL DEFAULT 'open' COMMENT '状态：open 持有中 / closed 已平仓',
    open_time            DATETIME        NULL COMMENT '开仓时间',
    close_time           DATETIME        NULL COMMENT '平仓时间',
    spot_qty             DECIMAL(38,18)  NULL COMMENT '现货腿数量（基础币）',
    futures_qty          DECIMAL(38,18)  NULL COMMENT '永续腿数量（基础币）',
    spot_entry_price     DECIMAL(38,18)  NULL COMMENT '现货腿开仓均价',
    futures_entry_price  DECIMAL(38,18)  NULL COMMENT '永续腿开仓均价',
    note                 VARCHAR(255)    NULL COMMENT '备注（决策依据、调仓原因等）',
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    updated_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本地更新时间',
    PRIMARY KEY (id),
    KEY idx_strategy_position_status (status, open_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='策略仓位（一组对冲组合，业务视角）';
