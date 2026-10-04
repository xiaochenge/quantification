package com.quantification.entity;

import java.time.LocalDateTime;

/**
 * 事件日志（对应表 {@code event_log}）：熔断、告警、下单失败、对账差异等"必须让人知道"的事件。
 *
 * <p>之前这些事件只写日志、不落库，后台的"异常与告警"页没有数据源；这张表给它们留痕，
 * 也方便事后追溯。
 */
public class EventLog {

    /** 主键。 */
    private Long id;

    /** 来源：system / mock / demo / real。 */
    private String source;

    /** 级别：INFO / WARN / ERROR。 */
    private String level;

    /** 类型：halt 熔断 / mail 邮件 / order 下单 / reconcile 对账。 */
    private String type;

    /** 一句话标题。 */
    private String title;

    /** 详情（原因 / 上下文）。 */
    private String detail;

    /** 本地入库时间。 */
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
