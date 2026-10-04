package com.quantification.service;

import com.quantification.entity.HostMetric;
import com.quantification.mapper.HostMetricMapper;
import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 主机资源监控（模块 10 后台"机器监控"页）。
 *
 * <p>定时采样这台机器的 CPU / 内存 / 磁盘 / 负载并落库，画历史曲线，用来判断机器够不够用、
 * 后续要不要换更强的机器。
 *
 * <p><b>开销</b>：数据全部来自 JVM 自带的 MXBean 与文件系统容量查询（内存 / 系统调用级别），
 * 不启子进程、不跑外部命令，默认 1 分钟采一条，性能开销可忽略。
 */
@Service
public class HostMonitorService {

    private static final Logger log = LoggerFactory.getLogger(HostMonitorService.class);

    /** 时间口径统一用 Asia/Shanghai。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 是否 macOS（macOS 的 JDK 不暴露交换区，需要另走 sysctl）。 */
    private static final boolean IS_MAC =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** 资源快照数据访问。 */
    private final HostMetricMapper hostMetricMapper;

    /** 监控哪个路径所在磁盘的容量（默认根卷）。 */
    private final String diskPath;

    public HostMonitorService(HostMetricMapper hostMetricMapper,
                              @Value("${host-monitor.disk-path:/}") String diskPath) {
        this.hostMetricMapper = hostMetricMapper;
        this.diskPath = diskPath;
    }

    /** 定时采样并落库（默认启动 20 秒后一次，之后每 1 分钟一次）。 */
    @Scheduled(initialDelayString = "${host-monitor.initial-delay-ms:20000}",
               fixedDelayString = "${host-monitor.sample-interval-ms:60000}")
    public void scheduledSample() {
        try {
            sample();
        } catch (Exception e) {
            // 采样失败不能影响其它任务
            log.warn("主机资源采样失败：{}", e.getMessage());
        }
    }

    /**
     * 采样一次并落库。
     *
     * @return 本次采到的快照
     */
    public HostMetric sample() {
        HostMetric metric = current();
        hostMetricMapper.insert(metric);
        return metric;
    }

    /**
     * 读取当前资源使用情况（不落库）。
     *
     * @return 当前快照
     */
    public HostMetric current() {
        HostMetric metric = new HostMetric();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        metric.setCpuCores(os.getAvailableProcessors());
        metric.setCpuLoadPct(percent(os.getCpuLoad()));
        metric.setProcessCpuLoadPct(percent(os.getProcessCpuLoad()));

        double loadAverage = os.getSystemLoadAverage();
        metric.setLoadAverage(loadAverage < 0
                ? null : BigDecimal.valueOf(loadAverage).setScale(2, RoundingMode.HALF_UP));

        long memTotal = os.getTotalMemorySize();
        long memFree = os.getFreeMemorySize();
        metric.setMemTotalBytes(memTotal > 0 ? memTotal : null);
        metric.setMemUsedBytes(memTotal > 0 ? Math.max(0, memTotal - memFree) : null);

        long swapTotal = os.getTotalSwapSpaceSize();
        long swapFree = os.getFreeSwapSpaceSize();
        if (swapTotal > 0 && swapFree >= 0) {
            metric.setSwapTotalBytes(swapTotal);
            metric.setSwapUsedBytes(Math.max(0, swapTotal - swapFree));
        } else {
            // macOS 走 sysctl（见方法注释）
            long[] macSwap = macSwapUsage();
            if (macSwap != null) {
                metric.setSwapTotalBytes(macSwap[0]);
                metric.setSwapUsedBytes(macSwap[1]);
            }
        }

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long heapUsed = memory.getHeapMemoryUsage().getUsed();
        long heapMax = memory.getHeapMemoryUsage().getMax();
        metric.setHeapUsedBytes(heapUsed > 0 ? heapUsed : null);
        metric.setHeapMaxBytes(heapMax > 0 ? heapMax : null);

        File disk = new File(diskPath);
        long diskTotal = disk.getTotalSpace();
        long diskUsable = disk.getUsableSpace();
        metric.setDiskTotalBytes(diskTotal > 0 ? diskTotal : null);
        metric.setDiskUsedBytes(diskTotal > 0 ? Math.max(0, diskTotal - diskUsable) : null);

        metric.setSampledAt(LocalDateTime.now(ZONE));
        return metric;
    }

    /**
     * 查最近若干条快照（倒序）。
     *
     * @param limit 取多少条
     * @return 快照列表
     */
    public List<HostMetric> recent(int limit) {
        return hostMetricMapper.findRecent(limit);
    }

    /** 把 0~1 的比率转成百分比（保留 2 位）；取不到（负数）时返回 null。 */
    private static BigDecimal percent(double ratio) {
        if (ratio < 0) {
            return null;
        }
        return BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * macOS 的 JDK 不通过 MXBean 暴露交换区（返回 -1），改用 {@code sysctl -n vm.swapusage} 读。
     *
     * <p>每分钟一次极轻量的系统调用，开销可忽略。输出形如：
     * {@code total = 2048.00M  used = 123.50M  free = 1924.50M}。
     *
     * @return {@code [总量字节, 已用字节]}；非 macOS 或读取失败返回 null
     */
    private static long[] macSwapUsage() {
        if (!IS_MAC) {
            return null;
        }
        try {
            Process process = new ProcessBuilder("/usr/sbin/sysctl", "-n", "vm.swapusage").start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            process.waitFor();
            long total = extractSize(output, "total =");
            long used = extractSize(output, "used =");
            if (total < 0 || used < 0) {
                return null;   // 解析失败
            }
            // 注意：total 可能是 0（macOS 未分配交换区时就是 0），0 是有效值，代表"没有交换、没有内存压力"
            return new long[]{total, Math.max(0, used)};
        } catch (Exception e) {
            log.warn("读取 macOS 交换区失败：{}", e.getMessage());
            return null;
        }
    }

    /** 从 {@code "total = 2048.00M used = 123.50M ..."} 中取出某个键对应的字节数。 */
    private static long extractSize(String text, String key) {
        int index = text.indexOf(key);
        if (index < 0) {
            return -1;
        }
        String rest = text.substring(index + key.length()).trim();
        int space = rest.indexOf(' ');
        return parseSize(space > 0 ? rest.substring(0, space) : rest);
    }

    /** 把 {@code "2048.00M"} / {@code "2.00G"} / {@code "512K"} / {@code "1024B"} 解析成字节。 */
    private static long parseSize(String token) {
        String value = token.trim().toUpperCase();
        BigDecimal factor = BigDecimal.ONE;
        if (value.endsWith("K")) {
            factor = BigDecimal.valueOf(1024L);
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("M")) {
            factor = BigDecimal.valueOf(1024L * 1024);
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("G")) {
            factor = BigDecimal.valueOf(1024L * 1024 * 1024);
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("T")) {
            factor = BigDecimal.valueOf(1024L * 1024 * 1024 * 1024);
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("B")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            return new BigDecimal(value.trim()).multiply(factor).longValue();
        } catch (Exception e) {
            return -1;
        }
    }
}
