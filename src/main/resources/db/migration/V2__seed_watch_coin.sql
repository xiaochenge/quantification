-- ============================================================
-- 初始化监控篮子。
-- 均为 Bitget 上现货与 U 本位永续都存在的币种（已按接口核对）。
-- Toncoin（TON）在 Bitget 未上架，已剔除。
-- 用 INSERT IGNORE 保证可重复执行。
-- ============================================================
INSERT IGNORE INTO watch_coin (base_coin, spot_symbol, futures_symbol, futures_category, enabled, note) VALUES
    ('BTC',  'BTCUSDT',  'BTCUSDT',  'USDT-FUTURES', 1, '市值第一'),
    ('ETH',  'ETHUSDT',  'ETHUSDT',  'USDT-FUTURES', 1, '市值第二'),
    ('XRP',  'XRPUSDT',  'XRPUSDT',  'USDT-FUTURES', 1, NULL),
    ('BNB',  'BNBUSDT',  'BNBUSDT',  'USDT-FUTURES', 1, NULL),
    ('SOL',  'SOLUSDT',  'SOLUSDT',  'USDT-FUTURES', 1, NULL),
    ('DOGE', 'DOGEUSDT', 'DOGEUSDT', 'USDT-FUTURES', 1, NULL),
    ('ADA',  'ADAUSDT',  'ADAUSDT',  'USDT-FUTURES', 1, NULL),
    ('TRX',  'TRXUSDT',  'TRXUSDT',  'USDT-FUTURES', 1, NULL),
    ('AVAX', 'AVAXUSDT', 'AVAXUSDT', 'USDT-FUTURES', 1, NULL),
    ('LINK', 'LINKUSDT', 'LINKUSDT', 'USDT-FUTURES', 1, NULL),
    ('DOT',  'DOTUSDT',  'DOTUSDT',  'USDT-FUTURES', 1, NULL),
    ('LTC',  'LTCUSDT',  'LTCUSDT',  'USDT-FUTURES', 1, NULL),
    ('SUI',  'SUIUSDT',  'SUIUSDT',  'USDT-FUTURES', 1, NULL),
    ('SHIB', 'SHIBUSDT', 'SHIBUSDT', 'USDT-FUTURES', 1, NULL);
