package com.quantification.mapper;

import com.quantification.entity.EventLog;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 事件日志（{@code event_log} 表）的数据访问。 */
@Mapper
public interface EventLogMapper {

    /**
     * 追加一条事件。
     *
     * @param row 事件
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO event_log (source, level, type, title, detail)
            VALUES (#{source}, #{level}, #{type}, #{title}, #{detail})
            """)
    int insert(EventLog row);

    /**
     * 查最近若干条事件（按时间倒序）。
     *
     * @param limit 取多少条
     * @return 事件列表（新的在前）
     */
    @Select("""
            SELECT id, source, level, type, title, detail, created_at
            FROM event_log
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<EventLog> findRecent(@Param("limit") int limit);
}
