package com.quantification.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 主机资源快照（对应表 {@code host_metric_snapshot}）：这台机器的 CPU / 内存 / 磁盘 / 负载。
 *
 * <p>用途：后台"机器监控"页展示当前值与历史曲线，判断机器是否够用、要不要换更强的机器。
 * 字节类的字段单位统一是"字节"，比率类字段单位是"百分比"（如 12.34 表示 12.34%）。
 */
public class HostMetric {

    /** 主键（当前实时采样时为空）。 */
    private Long id;

    /** 整机 CPU 使用率（%）。 */
    private BigDecimal cpuLoadPct;

    /** 本 Java 进程 CPU 使用率（%）。 */
    private BigDecimal processCpuLoadPct;

    /** 可用 CPU 核数。 */
    private Integer cpuCores;

    /** 系统 1 分钟平均负载。 */
    private BigDecimal loadAverage;

    /** 物理内存总量（字节）。 */
    private Long memTotalBytes;

    /** 物理内存已用（字节）。 */
    private Long memUsedBytes;

    /** JVM 堆已用（字节）。 */
    private Long heapUsedBytes;

    /** JVM 堆上限（字节）。 */
    private Long heapMaxBytes;

    /** 交换区总量（字节）。 */
    private Long swapTotalBytes;

    /** 交换区已用（字节）。 */
    private Long swapUsedBytes;

    /** 磁盘总量（字节）。 */
    private Long diskTotalBytes;

    /** 磁盘已用（字节）。 */
    private Long diskUsedBytes;

    /** 采样时间。 */
    private LocalDateTime sampledAt;

    /** 本地入库时间。 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public BigDecimal getCpuLoadPct() { return cpuLoadPct; }
    public void setCpuLoadPct(BigDecimal cpuLoadPct) { this.cpuLoadPct = cpuLoadPct; }

    public BigDecimal getProcessCpuLoadPct() { return processCpuLoadPct; }
    public void setProcessCpuLoadPct(BigDecimal processCpuLoadPct) { this.processCpuLoadPct = processCpuLoadPct; }

    public Integer getCpuCores() { return cpuCores; }
    public void setCpuCores(Integer cpuCores) { this.cpuCores = cpuCores; }

    public BigDecimal getLoadAverage() { return loadAverage; }
    public void setLoadAverage(BigDecimal loadAverage) { this.loadAverage = loadAverage; }

    public Long getMemTotalBytes() { return memTotalBytes; }
    public void setMemTotalBytes(Long memTotalBytes) { this.memTotalBytes = memTotalBytes; }

    public Long getMemUsedBytes() { return memUsedBytes; }
    public void setMemUsedBytes(Long memUsedBytes) { this.memUsedBytes = memUsedBytes; }

    public Long getHeapUsedBytes() { return heapUsedBytes; }
    public void setHeapUsedBytes(Long heapUsedBytes) { this.heapUsedBytes = heapUsedBytes; }

    public Long getHeapMaxBytes() { return heapMaxBytes; }
    public void setHeapMaxBytes(Long heapMaxBytes) { this.heapMaxBytes = heapMaxBytes; }

    public Long getSwapTotalBytes() { return swapTotalBytes; }
    public void setSwapTotalBytes(Long swapTotalBytes) { this.swapTotalBytes = swapTotalBytes; }

    public Long getSwapUsedBytes() { return swapUsedBytes; }
    public void setSwapUsedBytes(Long swapUsedBytes) { this.swapUsedBytes = swapUsedBytes; }

    public Long getDiskTotalBytes() { return diskTotalBytes; }
    public void setDiskTotalBytes(Long diskTotalBytes) { this.diskTotalBytes = diskTotalBytes; }

    public Long getDiskUsedBytes() { return diskUsedBytes; }
    public void setDiskUsedBytes(Long diskUsedBytes) { this.diskUsedBytes = diskUsedBytes; }

    public LocalDateTime getSampledAt() { return sampledAt; }
    public void setSampledAt(LocalDateTime sampledAt) { this.sampledAt = sampledAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
