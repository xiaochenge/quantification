-- ============================================================
-- 一期数据层建表。
--
-- 通用约定（详见 docs/coding-standards.md）：
--   * 时间：统一用 DATETIME 存可读时间（Asia/Shanghai），不用毫秒时间戳
--   * 金额 / 数量：DECIMAL(38,18)，避免浮点误差
--   * created_at / updated_at：本地入库 / 更新时间，由数据库自动维护
--   * 每个字段都写 COMMENT，含义直接看注释
-- ============================================================

-- ------------------------------------------------------------
-- 1. 交易对元数据：下单精度、最小下单量、乘数等规则
-- ------------------------------------------------------------
CREATE TABLE instrument (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    category            VARCHAR(32)     NOT NULL COMMENT '产品线：SPOT 现货 / USDT-FUTURES U本位合约 等',
    symbol              VARCHAR(64)     NOT NULL COMMENT '交易对，如 BTCUSDT',
    base_coin           VARCHAR(32)     NOT NULL COMMENT '基础币，如 BTC',
    quote_coin          VARCHAR(32)     NOT NULL COMMENT '计价币，如 USDT',
    contract_type       VARCHAR(32)     NULL COMMENT '合约类型（如 perpetual 永续）；现货为空',
    min_order_qty       DECIMAL(38,18)  NULL COMMENT '最小下单数量',
    max_order_qty       DECIMAL(38,18)  NULL COMMENT '单笔最大下单数量，0 表示不限',
    price_precision     INT             NULL COMMENT '价格精度（小数位数）',
    quantity_precision  INT             NULL COMMENT '数量精度（小数位数）',
    quote_precision     INT             NULL COMMENT '市价下单的计价精度（小数位数）',
    price_multiplier    DECIMAL(38,18)  NULL COMMENT '价格乘数（合约下单用，配合价格精度）',
    quantity_multiplier DECIMAL(38,18)  NULL COMMENT '数量乘数（合约下单用，配合数量精度）',
    maker_fee_rate      DECIMAL(38,18)  NULL COMMENT '挂单费率（仅合约返回）',
    taker_fee_rate      DECIMAL(38,18)  NULL COMMENT '吃单费率（仅合约返回）',
    source_time         DATETIME        NULL COMMENT '本条数据的时间（交易所给的或本地刷新时间）',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    updated_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本地更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_instrument (category, symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交易对元数据';

-- ------------------------------------------------------------
-- 2. 监控篮子：我们主动选定要做的币种
-- ------------------------------------------------------------
CREATE TABLE watch_coin (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    base_coin        VARCHAR(32)     NOT NULL COMMENT '标的币种，如 BTC',
    spot_symbol      VARCHAR(64)     NOT NULL COMMENT '对应的现货交易对',
    futures_symbol   VARCHAR(64)     NOT NULL COMMENT '对应的永续交易对',
    futures_category VARCHAR(32)     NOT NULL DEFAULT 'USDT-FUTURES' COMMENT '永续所属产品线',
    enabled          TINYINT         NOT NULL DEFAULT 1 COMMENT '是否启用：1 启用 / 0 停用',
    note             VARCHAR(255)    NULL COMMENT '备注',
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    updated_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本地更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_watch_coin_base (base_coin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='监控币种篮子';

-- ------------------------------------------------------------
-- 3. 历史资金费率：每个交易对每个结算点一条（核心数据）
-- ------------------------------------------------------------
CREATE TABLE funding_rate_history (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    symbol       VARCHAR(64)     NOT NULL COMMENT '交易对，如 BTCUSDT',
    category     VARCHAR(32)     NOT NULL COMMENT '产品线，如 USDT-FUTURES',
    funding_rate DECIMAL(38,18)  NOT NULL COMMENT '本次结算的资金费率（小数，0.0001 表示 0.01%）',
    funding_time DATETIME        NOT NULL COMMENT '结算时间（Asia/Shanghai）',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_frh_symbol_time (symbol, funding_time),
    KEY idx_frh_time (funding_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='历史资金费率（每结算点一条，幂等靠 symbol+funding_time）';

-- ------------------------------------------------------------
-- 4. 实时资金费率：每个币只保留最新一条（覆盖写）
-- ------------------------------------------------------------
CREATE TABLE funding_rate_current (
    id                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    symbol                VARCHAR(64)     NOT NULL COMMENT '交易对，如 BTCUSDT',
    category              VARCHAR(32)     NOT NULL COMMENT '产品线，如 USDT-FUTURES',
    funding_rate          DECIMAL(38,18)  NULL COMMENT '当前资金费率（小数）',
    funding_rate_interval INT             NULL COMMENT '结算周期，单位小时（1 / 2 / 4 / 8）',
    next_update_time      DATETIME        NULL COMMENT '下次结算时间（Asia/Shanghai）',
    min_funding_rate      DECIMAL(38,18)  NULL COMMENT '资金费率下限',
    max_funding_rate      DECIMAL(38,18)  NULL COMMENT '资金费率上限',
    source_time           DATETIME        NULL COMMENT '本条数据的采集时间',
    created_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    updated_at            DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '本地更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_frc_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实时资金费率快照（每个币一条）';

-- ------------------------------------------------------------
-- 5. 行情快照：只在事件时写入（下单 / 平仓前后）
-- ------------------------------------------------------------
CREATE TABLE ticker_snapshot (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    symbol        VARCHAR(64)     NOT NULL COMMENT '交易对',
    category      VARCHAR(32)     NOT NULL COMMENT '产品线',
    last_price    DECIMAL(38,18)  NULL COMMENT '最新成交价',
    bid1_price    DECIMAL(38,18)  NULL COMMENT '买一价',
    ask1_price    DECIMAL(38,18)  NULL COMMENT '卖一价',
    bid1_size     DECIMAL(38,18)  NULL COMMENT '买一数量',
    ask1_size     DECIMAL(38,18)  NULL COMMENT '卖一数量',
    mark_price    DECIMAL(38,18)  NULL COMMENT '标记价格（仅合约）',
    index_price   DECIMAL(38,18)  NULL COMMENT '指数价格（仅合约）',
    funding_rate  DECIMAL(38,18)  NULL COMMENT '当前资金费率（仅合约）',
    open_interest DECIMAL(38,18)  NULL COMMENT '持仓量（仅合约）',
    turnover24h   DECIMAL(38,18)  NULL COMMENT '24 小时成交额',
    volume24h     DECIMAL(38,18)  NULL COMMENT '24 小时成交量',
    source_time   DATETIME        NULL COMMENT '本条行情的时间（交易所给的或采集时间）',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    KEY idx_ticker_symbol_time (symbol, source_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='行情快照（事件时写入）';

-- ------------------------------------------------------------
-- 6. 账户权益快照：每小时一次
-- ------------------------------------------------------------
CREATE TABLE account_balance_snapshot (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    source              VARCHAR(16)     NOT NULL DEFAULT 'real' COMMENT '数据来源：real 实盘 / mock 模拟',
    account_equity_usd  DECIMAL(38,18)  NULL COMMENT '账户总权益（USD）',
    usdt_equity         DECIMAL(38,18)  NULL COMMENT '账户总权益（USDT）',
    btc_equity          DECIMAL(38,18)  NULL COMMENT '账户总权益（BTC）',
    unrealised_pnl_usd  DECIMAL(38,18)  NULL COMMENT '账户总未实现盈亏（USD）',
    usdt_unrealised_pnl DECIMAL(38,18)  NULL COMMENT '账户总未实现盈亏（USDT）',
    eff_equity          DECIMAL(38,18)  NULL COMMENT '有效权益（可作保证金的资产净值，USD）',
    mmr                 DECIMAL(38,18)  NULL COMMENT '维持保证金（USD）',
    imr                 DECIMAL(38,18)  NULL COMMENT '初始保证金（USD）',
    mgn_ratio           DECIMAL(38,18)  NULL COMMENT '维持保证金率',
    position_value      DECIMAL(38,18)  NULL COMMENT '仓位价值（USD）',
    leverage            DECIMAL(38,18)  NULL COMMENT '账户杠杆',
    source_time         DATETIME        NULL COMMENT '快照时间',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    KEY idx_abs_source_time (source_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户权益快照（每小时一次）';

-- ------------------------------------------------------------
-- 7. 币种资产快照：每小时一次，与账户权益快照同一次采集
-- ------------------------------------------------------------
CREATE TABLE account_asset_snapshot (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    balance_snapshot_id BIGINT UNSIGNED NOT NULL COMMENT '关联 account_balance_snapshot.id',
    coin                VARCHAR(32)     NOT NULL COMMENT '币种',
    equity              DECIMAL(38,18)  NULL COMMENT '该币种权益（以币种计）',
    usd_value           DECIMAL(38,18)  NULL COMMENT '折算 USD 价值',
    balance             DECIMAL(38,18)  NULL COMMENT '余额',
    available           DECIMAL(38,18)  NULL COMMENT '可用数量',
    locked              DECIMAL(38,18)  NULL COMMENT '下单占用（仅现货下单场景有值）',
    debt                DECIMAL(38,18)  NULL COMMENT '负债数量',
    source_time         DATETIME        NULL COMMENT '快照时间',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    KEY idx_aas_snapshot (balance_snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='币种资产快照（每小时一次）';

-- ------------------------------------------------------------
-- 8. 持仓快照：每小时一次（交易所视角的真实持仓）
-- ------------------------------------------------------------
CREATE TABLE position_snapshot (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    source           VARCHAR(16)     NOT NULL DEFAULT 'real' COMMENT '数据来源：real / mock',
    symbol           VARCHAR(64)     NOT NULL COMMENT '交易对',
    category         VARCHAR(32)     NOT NULL COMMENT '产品线',
    margin_coin      VARCHAR(32)     NULL COMMENT '保证金币种',
    pos_side         VARCHAR(16)     NULL COMMENT '持仓方向：long 多 / short 空',
    position_balance DECIMAL(38,18)  NULL COMMENT '仓位保证金数量（以保证金币种计）',
    available        DECIMAL(38,18)  NULL COMMENT '仓位可用数量（基础币）',
    frozen           DECIMAL(38,18)  NULL COMMENT '仓位冻结数量（基础币，平仓单占用）',
    total            DECIMAL(38,18)  NULL COMMENT '仓位总数量（可用 + 冻结）',
    leverage         DECIMAL(38,18)  NULL COMMENT '杠杆倍数',
    avg_price        DECIMAL(38,18)  NULL COMMENT '平均开仓价',
    unrealised_pnl   DECIMAL(38,18)  NULL COMMENT '未实现盈亏',
    cur_realised_pnl DECIMAL(38,18)  NULL COMMENT '已实现盈亏（不含手续费和资金费）',
    margin_mode      VARCHAR(16)     NULL COMMENT '保证金模式：crossed 全仓 / isolated 逐仓',
    hold_mode        VARCHAR(16)     NULL COMMENT '持仓模式：one_way_mode 单向 / hedge_mode 双向',
    source_time      DATETIME        NULL COMMENT '快照时间',
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    KEY idx_ps_symbol_time (symbol, source_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='持仓快照（每小时一次）';

-- ------------------------------------------------------------
-- 9. 账户财务流水：资金费真实到账的权威来源
-- ------------------------------------------------------------
CREATE TABLE account_financial_record (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    record_id        VARCHAR(64)     NOT NULL COMMENT '交易所流水 ID（幂等键）',
    category         VARCHAR(32)     NULL COMMENT '产品线',
    symbol           VARCHAR(64)     NULL COMMENT '交易对',
    coin             VARCHAR(32)     NULL COMMENT '币种',
    type             VARCHAR(64)     NULL COMMENT '流水类型，资金费通过此字段筛选',
    position_type    VARCHAR(32)     NULL COMMENT '仓位类型：crossed 全仓 / isolated 逐仓',
    fee              DECIMAL(38,18)  NULL COMMENT '手续费（交易 / 提币）',
    position_amount  DECIMAL(38,18)  NULL COMMENT '仓位变动数量',
    position_balance DECIMAL(38,18)  NULL COMMENT '仓位余额',
    amount           DECIMAL(38,18)  NULL COMMENT '变动金额',
    balance          DECIMAL(38,18)  NULL COMMENT '变动后账户剩余资产',
    source_time      DATETIME        NULL COMMENT '流水发生时间',
    created_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_afr_record (record_id),
    KEY idx_afr_symbol_time (symbol, source_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='账户财务流水';

-- ------------------------------------------------------------
-- 10. 手续费率快照：费率会随 VIP / 活动变化，必须记录当时值
-- ------------------------------------------------------------
CREATE TABLE fee_rate (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    symbol         VARCHAR(64)     NOT NULL COMMENT '交易对',
    category       VARCHAR(32)     NOT NULL COMMENT '产品线',
    maker_fee_rate DECIMAL(38,18)  NULL COMMENT '挂单费率（小数）',
    taker_fee_rate DECIMAL(38,18)  NULL COMMENT '吃单费率（小数）',
    rpi_flag       VARCHAR(8)      NULL COMMENT '是否 RPI 费率：yes / no',
    source_time    DATETIME        NULL COMMENT '采集时间',
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    KEY idx_fee_symbol_time (symbol, category, source_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='手续费率快照';
