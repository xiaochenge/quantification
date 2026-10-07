package com.quantification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.quantification.service.WatchdogService.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WatchdogService#evaluate} 的单元测试。
 *
 * <p>为什么重要：看门狗的判定直接决定"沉默型故障能不能被及时发出来"。
 * 判早了会误报（没事就发邮件，久了就没人看）；判晚了或只发一次，
 * 就又会退回"卡死十几个小时没人知道"的老问题。
 */
class WatchdogServiceTest {

    /** 心跳阈值 2 小时、提醒间隔 1 小时。 */
    private static final long STALE_MS = 7_200_000L;
    private static final long REMIND_MS = 3_600_000L;

    @Test
    @DisplayName("心跳新鲜 → 什么都不做")
    void freshHeartbeatDoesNothing() {
        long now = 10_000_000L;
        assertEquals(Action.NONE, WatchdogService.evaluate(now, now - 1_000L, STALE_MS, 0L, REMIND_MS));
    }

    @Test
    @DisplayName("刚超过阈值且没告警过 → 发第一封告警")
    void staleFirstTimeAlerts() {
        long now = 10_000_000L;
        assertEquals(Action.ALERT,
                WatchdogService.evaluate(now, now - STALE_MS - 1, STALE_MS, 0L, REMIND_MS));
    }

    @Test
    @DisplayName("已告警但未到提醒间隔 → 不重复发")
    void staleWithinRemindWindowStaysQuiet() {
        long now = 10_000_000L;
        long lastAlertAt = now - REMIND_MS + 1;
        assertEquals(Action.NONE,
                WatchdogService.evaluate(now, now - STALE_MS - 1, STALE_MS, lastAlertAt, REMIND_MS));
    }

    @Test
    @DisplayName("一直没恢复、已过提醒间隔 → 再提醒一次")
    void staleAfterRemindWindowReminds() {
        long now = 10_000_000L;
        long lastAlertAt = now - REMIND_MS;
        assertEquals(Action.REMIND,
                WatchdogService.evaluate(now, now - STALE_MS - 1, STALE_MS, lastAlertAt, REMIND_MS));
    }

    @Test
    @DisplayName("心跳恢复且此前告过警 → 发一封恢复通知")
    void recoveredAfterAlertNotifies() {
        long now = 10_000_000L;
        assertEquals(Action.RECOVER,
                WatchdogService.evaluate(now, now - 1_000L, STALE_MS, now - 5_000_000L, REMIND_MS));
    }

    @Test
    @DisplayName("心跳恢复但本来就没告警 → 不重复发恢复通知")
    void recoveredWithoutPriorAlertStaysQuiet() {
        long now = 10_000_000L;
        assertEquals(Action.NONE, WatchdogService.evaluate(now, now - 1_000L, STALE_MS, 0L, REMIND_MS));
    }
}
