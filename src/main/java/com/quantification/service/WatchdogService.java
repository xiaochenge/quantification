package com.quantification.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 看门狗：盯"程序还活着、但什么都不干"的<b>沉默型故障</b>。
 *
 * <p><b>为什么需要</b>：2026-10-06 的故障是"对端连接半开 → 本地读永久挂起 → 持锁线程卡死"，
 * 结果是管理后台打不开、mock 十几个小时不结算、不调仓。整个过程<b>没有抛任何异常、也没触发熔断</b>，
 * 所以"熔断告警"和"连续网络异常告警"两条路都够不着它——故障是无声发生的，只能靠人偶然发现。
 * 本类补的就是这个盲区：关键循环每跑完一轮就更新一次心跳，心跳停了就发邮件。
 *
 * <p><b>为什么用独立线程而不是 {@code @Scheduled}</b>：Spring 的定时任务共用一个线程池。
 * 故障时若几个任务一起卡住，把池子占满，{@code @Scheduled} 的看门狗自己也就跑不起来了。
 * 这里用一条独占的守护线程，且检查过程<b>只读内存、不碰网络、不碰任何业务锁</b>，
 * 保证它不会和故障一起被拖死。
 *
 * <p>配置见 {@code alert.watchdog} 段。
 */
@Service
public class WatchdogService {

    private static final Logger log = LoggerFactory.getLogger(WatchdogService.class);

    /** 看门狗该做什么。 */
    enum Action {
        /** 什么都不做。 */
        NONE,
        /** 刚发现卡死，发第一封告警。 */
        ALERT,
        /** 还没恢复，按间隔再提醒一次。 */
        REMIND,
        /** 已恢复，发一封恢复通知。 */
        RECOVER
    }

    private final MailAlertService mailAlert;
    private final boolean enabled;
    private final long checkIntervalMs;
    private final long staleMs;
    private final long remindMs;

    /** 最近一次心跳时间（毫秒）。 */
    private final AtomicLong lastAliveAt = new AtomicLong();

    /** 最近一次心跳来自哪里（用于告警正文）。 */
    private final AtomicReference<String> lastAliveSource = new AtomicReference<>("程序启动");

    /** 最近一次发出"卡死"告警的时间；0 表示当前不处于告警状态。 */
    private volatile long lastAlertAt = 0L;

    /** 看门狗自己的线程（守护线程，不阻止 JVM 退出）。 */
    private ScheduledExecutorService executor;

    public WatchdogService(MailAlertService mailAlert,
                           @Value("${alert.watchdog.enabled:true}") boolean enabled,
                           @Value("${alert.watchdog.check-interval-ms:300000}") long checkIntervalMs,
                           @Value("${alert.watchdog.stale-ms:9000000}") long staleMs,
                           @Value("${alert.watchdog.remind-ms:3600000}") long remindMs) {
        this.mailAlert = mailAlert;
        this.enabled = enabled;
        this.checkIntervalMs = checkIntervalMs;
        this.staleMs = staleMs;
        this.remindMs = remindMs;
        // 启动即视为心跳，避免刚起来还没跑第一轮就误报
        this.lastAliveAt.set(System.currentTimeMillis());
    }

    /** 启动看门狗线程。 */
    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("看门狗未启用（alert.watchdog.enabled=false）");
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::check, checkIntervalMs, checkIntervalMs, TimeUnit.MILLISECONDS);
        log.info("看门狗已启用：每 {}检查一次，心跳停摆超过 {}即告警",
                human(checkIntervalMs), human(staleMs));
    }

    /** 关闭看门狗线程。 */
    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 打一次心跳。关键循环每完整跑完一轮就调用一次。
     *
     * <p>注意：必须在"整轮跑完"时调用——如果中途卡住，这行永远执行不到，看门狗才能发现。
     *
     * @param source 心跳来源（写进告警正文，便于定位是哪条链路停了）
     */
    public void markAlive(String source) {
        lastAliveAt.set(System.currentTimeMillis());
        lastAliveSource.set(source);
    }

    /** 周期性检查心跳是否停摆。任何异常都不允许打断看门狗自己。 */
    private void check() {
        try {
            long now = System.currentTimeMillis();
            long lastAlive = lastAliveAt.get();
            Action action = evaluate(now, lastAlive, staleMs, lastAlertAt, remindMs);
            if (action == Action.NONE) {
                return;
            }
            String stale = human(now - lastAlive);
            if (action == Action.RECOVER) {
                lastAlertAt = 0L;
                log.info("看门狗：心跳恢复（静默 {}后恢复）", stale);
                mailAlert.send("策略循环已恢复（看门狗）",
                        "策略循环的心跳已恢复，程序重新开始正常推进。\n"
                                + "上次心跳来源：" + lastAliveSource.get() + "\n"
                                + "恢复时间：" + LocalDateTime.now() + "\n"
                                + "提示：请确认这段时间内错过的资金费 / 调仓已自动补上。");
                return;
            }
            lastAlertAt = now;
            String title = action == Action.ALERT ? "策略循环疑似卡死（看门狗）" : "策略循环仍未恢复（看门狗）";
            log.error("看门狗：{}，最近一次心跳在 {} 前（来源：{}）", title, stale, lastAliveSource.get());
            mailAlert.send(title,
                    "策略循环已经 " + stale + "没有跑完一轮了，疑似卡死（不是网络抖动、也不是熔断）。\n"
                            + "最近一次心跳来源：" + lastAliveSource.get() + "\n"
                            + "最近一次心跳时间："
                            + LocalDateTime.ofInstant(Instant.ofEpochMilli(lastAlive), ZoneId.systemDefault()) + "\n"
                            + "可能原因：某个线程卡在外部请求上不放锁、或定时任务线程被占满。\n"
                            + "建议：查日志与应用线程栈，必要时重启程序（重启后 mock 状态会从数据库恢复）。");
        } catch (Exception e) {
            // 看门狗绝不能因为自己的问题停摆
            log.warn("看门狗检查异常（忽略，下个周期继续）：{}", e.getMessage());
        }
    }

    /**
     * 判定该做什么（纯函数，便于单测）。
     *
     * @param now         当前时间（毫秒）
     * @param lastAliveAt 最近一次心跳时间（毫秒）
     * @param staleMs     超过多久没心跳算卡死
     * @param lastAlertAt 最近一次卡死告警时间；0 表示当前不在告警状态
     * @param remindMs    未恢复时多久再提醒一次
     * @return 该执行的动作
     */
    static Action evaluate(long now, long lastAliveAt, long staleMs, long lastAlertAt, long remindMs) {
        if (now - lastAliveAt > staleMs) {
            if (lastAlertAt == 0L) {
                return Action.ALERT;
            }
            return now - lastAlertAt >= remindMs ? Action.REMIND : Action.NONE;
        }
        return lastAlertAt == 0L ? Action.NONE : Action.RECOVER;
    }

    /**
     * 把毫秒时长写成"人读得懂"的文本（整分钟按分钟，否则按秒）。
     *
     * <p>为什么不直接用 {@code Duration.toMinutes()}：验证时用 30 秒的检查间隔会被整除成
     * "每 0 分钟检查一次"，看着像没生效。
     */
    private static String human(long millis) {
        return millis % 60_000L == 0 ? (millis / 60_000L) + " 分钟" : (millis / 1000L) + " 秒";
    }
}
