package com.quantification.mapper;

import com.quantification.entity.HostMetric;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 主机资源快照（{@code host_metric_snapshot} 表）的数据访问。 */
@Mapper
public interface HostMetricMapper {

    /**
     * 追加一条主机资源快照。
     *
     * @param row 快照
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO host_metric_snapshot
                (cpu_load_pct, process_cpu_load_pct, cpu_cores, load_average,
                 mem_total_bytes, mem_used_bytes, heap_used_bytes, heap_max_bytes,
                 swap_total_bytes, swap_used_bytes, disk_total_bytes, disk_used_bytes, sampled_at)
            VALUES
                (#{cpuLoadPct}, #{processCpuLoadPct}, #{cpuCores}, #{loadAverage},
                 #{memTotalBytes}, #{memUsedBytes}, #{heapUsedBytes}, #{heapMaxBytes},
                 #{swapTotalBytes}, #{swapUsedBytes}, #{diskTotalBytes}, #{diskUsedBytes}, #{sampledAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(HostMetric row);

    /**
     * 查最近若干条快照（按时间倒序，前端画曲线时再反序）。
     *
     * @param limit 取多少条
     * @return 快照列表（新的在前）
     */
    @Select("""
            SELECT id, cpu_load_pct, process_cpu_load_pct, cpu_cores, load_average,
                   mem_total_bytes, mem_used_bytes, heap_used_bytes, heap_max_bytes,
                   swap_total_bytes, swap_used_bytes, disk_total_bytes, disk_used_bytes,
                   sampled_at, created_at
            FROM host_metric_snapshot
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<HostMetric> findRecent(@Param("limit") int limit);
}
