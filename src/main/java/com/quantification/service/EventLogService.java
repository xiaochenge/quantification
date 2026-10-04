package com.quantification.service;

import com.quantification.entity.EventLog;
import com.quantification.mapper.EventLogMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 事件日志服务：把"必须让人知道"的事件落库，供后台"异常与告警"页展示。
 *
 * <p><b>约束</b>：写事件绝不能反噬调用方——插入失败只记日志、不抛异常，
 * 否则熔断/下单路径会因为"记事件失败"而被拖崩。
 */
@Service
public class EventLogService {

    private static final Logger log = LoggerFactory.getLogger(EventLogService.class);

    /** 事件日志数据访问。 */
    private final EventLogMapper eventLogMapper;

    public EventLogService(EventLogMapper eventLogMapper) {
        this.eventLogMapper = eventLogMapper;
    }

    /**
     * 记录一条事件（失败静默降级为日志）。
     *
     * @param source 来源（system / mock / demo / real）
     * @param level  级别（INFO / WARN / ERROR）
     * @param type   类型（halt / mail / order / reconcile）
     * @param title  一句话标题
     * @param detail 详情
     */
    public void log(String source, String level, String type, String title, String detail) {
        try {
            EventLog row = new EventLog();
            row.setSource(source == null ? "system" : source);
            row.setLevel(level);
            row.setType(type);
            row.setTitle(title);
            row.setDetail(detail);
            eventLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("事件落库失败（不影响主流程）：{} {} {}", level, type, title);
        }
    }

    /**
     * 查最近若干条事件。
     *
     * @param limit 取多少条
     * @return 事件列表（新的在前）
     */
    public List<EventLog> recent(int limit) {
        return eventLogMapper.findRecent(limit);
    }
}
