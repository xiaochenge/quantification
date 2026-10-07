package com.quantification.service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

/**
 * 邮件告警（模块 6 的告警渠道，最小实现）。
 *
 * <p><b>为什么需要</b>：程序是无人值守跑的，熔断 / 下单失败 / 长时间断网这类事件
 * 如果只写日志，很可能几天后才被发现。这里把"必须人工知道"的事件发一封邮件。
 *
 * <p><b>设计约束</b>：
 * <ul>
 *   <li><b>绝不反噬调用方</b>：发信失败只记 ERROR，不往外抛——告警不能反过来影响交易 / 风控；</li>
 *   <li><b>节流</b>：同一主题在冷却时间内只发一封，避免一个事件每轮循环重复发造成轰炸；</li>
 *   <li><b>不泄露密钥</b>：日志里永远不打印 SMTP 密码与邮件正文里的敏感字段。</li>
 * </ul>
 *
 * <p>配置见 application.yml 的 {@code alert.mail} 段；密码只放仓库外
 * {@code ~/.quantification/application-local.yml} 的 {@code alert.mail.password}。
 */
@Service
public class MailAlertService {

    private static final Logger log = LoggerFactory.getLogger(MailAlertService.class);

    /** 是否启用邮件告警。 */
    private final boolean enabled;

    /** 同一主题的节流间隔（秒）。 */
    private final int cooldownSeconds;

    /** 收件人列表（可能为空表示未配置）。 */
    private final List<String> to;

    /** 发件人地址（留空时回退到 username）。 */
    private final String from;

    /** 邮件发送器；未启用或配置不完整时为 null。 */
    private final JavaMailSenderImpl sender;

    /** 事件日志：把"邮件已发 / 发送失败"落库，后台可看发送记录。 */
    private final EventLogService eventLog;

    /** 每个主题最近一次发送时间（毫秒），用于节流。 */
    private final Map<String, Long> lastSentAt = new ConcurrentHashMap<>();

    public MailAlertService(EventLogService eventLog,
                            @Value("${alert.mail.enabled:false}") boolean enabled,
                            @Value("${alert.mail.host:}") String host,
                            @Value("${alert.mail.port:465}") int port,
                            @Value("${alert.mail.username:}") String username,
                            @Value("${alert.mail.password:}") String password,
                            @Value("${alert.mail.from:}") String from,
                            @Value("${alert.mail.to:}") String to,
                            @Value("${alert.mail.cooldown-seconds:300}") int cooldownSeconds) {
        this.eventLog = eventLog;
        this.enabled = enabled;
        this.cooldownSeconds = cooldownSeconds;
        this.to = parseToList(to);
        this.from = from == null || from.isBlank() ? username : from;

        if (!enabled) {
            this.sender = null;
            log.info("邮件告警未启用（alert.mail.enabled=false）");
        } else if (host == null || host.isBlank() || username == null || username.isBlank()
                || password == null || password.isBlank() || this.to.isEmpty()) {
            this.sender = null;
            log.warn("邮件告警已启用但配置不完整（host / username / password / to 有缺失），"
                    + "将只记录日志、不发送邮件");
        } else {
            this.sender = buildSender(host, port, username, password);
            log.info("邮件告警已启用：SMTP {}:{}，发件 {}，收件 {} 个",
                    host, port, this.from, this.to.size());
        }
    }

    /**
     * 发送一封告警邮件（发送失败或被节流时只记日志，绝不抛异常）。
     *
     * @param subject 主题（会自动加 [quantification] 前缀）
     * @param body    正文
     */
    public void send(String subject, String body) {
        if (sender == null || to.isEmpty()) {
            log.warn("告警（未发送邮件）[{}] {}", subject, body);
            return;
        }
        if (!allow(subject)) {
            log.info("告警已在冷却期内，跳过重复发送：[{}]", subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to.toArray(new String[0]));
            message.setSubject("[quantification] " + subject);
            message.setText(body);
            sender.send(message);
            log.info("告警邮件已发送：[{}]", subject);
            eventLog.log("system", "INFO", "mail", "告警邮件已发送：" + subject, "收件人：" + String.join(",", to));
        } catch (Exception e) {
            // 关键：发信失败绝不能抛出去，否则会把交易循环 / 风控一起拖崩
            log.error("告警邮件发送失败 [{}]：{}", subject, e.getMessage());
            eventLog.log("system", "ERROR", "mail", "告警邮件发送失败：" + subject, e.getMessage());
        }
    }

    /** 节流：同一主题在冷却时间内只放行一次。 */
    private boolean allow(String topic) {
        long now = System.currentTimeMillis();
        long cooldownMs = cooldownSeconds * 1000L;
        Long last = lastSentAt.get(topic);
        if (last != null && now - last < cooldownMs) {
            return false;
        }
        lastSentAt.put(topic, now);
        return true;
    }

    /** 按 host / port / 账号构建 JavaMail 发送器。 */
    private static JavaMailSenderImpl buildSender(String host, int port, String username, String password) {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(host);
        mailSender.setPort(port);
        mailSender.setUsername(username);
        mailSender.setPassword(password);
        mailSender.setDefaultEncoding("UTF-8");
        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.smtp.auth", "true");
        // 发信同样必须有超时：没有超时的 SMTP 连接一旦被对端半开，调用线程会永远挂住。
        // 看门狗就靠这条线程发告警，它自己被挂住就等于告警失效。
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        // 465 走 SSL，其余端口（如 587）走 STARTTLS
        if (port == 465) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        return mailSender;
    }

    /** 把逗号分隔的收件人字符串拆成列表，忽略空白项。 */
    private static List<String> parseToList(String to) {
        if (to == null || to.isBlank()) {
            return List.of();
        }
        return Arrays.stream(to.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
