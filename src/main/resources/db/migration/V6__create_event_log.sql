-- ============================================================
-- 事件日志表（模块 10 管理后台"异常与告警"页的数据源；同时给模块 6 的告警留痕）。
--
-- 之前熔断 / 邮件发送 / 对账差异只写日志、不留库，后台没法展示。
-- 这张表把"必须让人知道"的事件落一条，便于事后追溯与看板展示。
-- ============================================================
CREATE TABLE event_log (
    id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    source     VARCHAR(16)     NOT NULL DEFAULT 'system' COMMENT '来源：system 系统 / mock / demo / real',
    level      VARCHAR(8)      NOT NULL COMMENT '级别：INFO / WARN / ERROR',
    type       VARCHAR(32)     NOT NULL COMMENT '类型：halt 熔断 / mail 邮件 / order 下单 / reconcile 对账',
    title      VARCHAR(255)    NOT NULL COMMENT '一句话标题',
    detail     VARCHAR(2000)   NULL COMMENT '详情（原因 / 上下文）',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    KEY idx_event_log_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事件日志（熔断/告警/下单/对账）';
