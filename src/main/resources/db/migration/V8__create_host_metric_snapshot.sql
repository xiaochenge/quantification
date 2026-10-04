-- ============================================================
-- 主机资源快照（模块 10 后台"机器监控"页的数据源）。
--
-- 目的：定期记录这台 Mac 的 CPU / 内存 / 磁盘 / 负载，画历史曲线，
-- 用来判断"当前机器是否够用、要不要换更强的机器"。
--
-- 采样代价极低（读 JDK MXBean + 文件系统容量，都是内存/系统调用），
-- 默认每 1 分钟一条，90 天约 13 万行，可以长期保留。
-- ============================================================
CREATE TABLE host_metric_snapshot (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    cpu_load_pct         DECIMAL(8,4)    NULL COMMENT '整机 CPU 使用率（%）',
    process_cpu_load_pct DECIMAL(8,4)    NULL COMMENT '本 Java 进程 CPU 使用率（%）',
    cpu_cores            INT             NULL COMMENT '可用 CPU 核数',
    load_average         DECIMAL(8,4)    NULL COMMENT '系统 1 分钟平均负载',
    mem_total_bytes      BIGINT UNSIGNED NULL COMMENT '物理内存总量（字节）',
    mem_used_bytes       BIGINT UNSIGNED NULL COMMENT '物理内存已用（字节）',
    heap_used_bytes      BIGINT UNSIGNED NULL COMMENT 'JVM 堆已用（字节）',
    heap_max_bytes       BIGINT UNSIGNED NULL COMMENT 'JVM 堆上限（字节）',
    swap_total_bytes     BIGINT UNSIGNED NULL COMMENT '交换区总量（字节）',
    swap_used_bytes      BIGINT UNSIGNED NULL COMMENT '交换区已用（字节）',
    disk_total_bytes     BIGINT UNSIGNED NULL COMMENT '磁盘总量（字节）',
    disk_used_bytes      BIGINT UNSIGNED NULL COMMENT '磁盘已用（字节）',
    sampled_at           DATETIME        NOT NULL COMMENT '采样时间',
    created_at           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本地入库时间',
    PRIMARY KEY (id),
    KEY idx_host_metric_time (sampled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='主机资源快照（CPU/内存/磁盘/负载）';
