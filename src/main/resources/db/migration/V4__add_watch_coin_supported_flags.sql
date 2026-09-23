-- ============================================================
-- 给监控篮子加"实盘支持 / 模拟盘支持"两个标记。
--
-- 背景：官方模拟盘只支持少数币种（实测现货与合约交集只有 BTC/ETH/DOGE/SOL），
-- 而策略候选多为中小币——在模拟盘里根本下不了单。
-- 做法：同步篮子时同时拉"实盘产品清单"和"模拟盘产品清单"，把结果记在这两个字段上，
-- 程序按当前运行模式（paptrading）自动过滤，不需要额外配置。
-- ============================================================
ALTER TABLE watch_coin
    ADD COLUMN real_supported TINYINT NOT NULL DEFAULT 0 COMMENT '实盘是否有该币的现货+永续' AFTER discount_rate,
    ADD COLUMN demo_supported TINYINT NOT NULL DEFAULT 0 COMMENT '模拟盘是否有该币的现货+永续' AFTER real_supported;
