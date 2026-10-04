package com.quantification.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * {@link TradingLoopService#isTransient} 的单元测试。
 *
 * <p>为什么重要：这个判断决定"评估失败到底是自动重试还是直接熔断"。网络抖动误判成熔断，
 * 无人值守的策略就会静默停摆；真错误误判成网络，又会带病继续。所以边界要测清楚。
 */
class TransientFailureTest {

    @Test
    @DisplayName("Spring 网络异常（ResourceAccessException）算瞬时")
    void resourceAccessIsTransient() {
        assertTrue(TradingLoopService.isTransient(new ResourceAccessException("I/O error on GET")));
    }

    @Test
    @DisplayName("cause 链里的 SSL 握手失败算瞬时")
    void sslHandshakeInCauseChainIsTransient() {
        Throwable outer = new IllegalStateException("wrapped", new SSLHandshakeException("handshake terminated"));
        assertTrue(TradingLoopService.isTransient(outer));
    }

    @Test
    @DisplayName("普通业务异常不算瞬时（应熔断）")
    void businessExceptionIsNotTransient() {
        assertFalse(TradingLoopService.isTransient(new IllegalArgumentException("参数错误")));
    }

    @Test
    @DisplayName("空指针等未知异常不算瞬时（应熔断）")
    void unknownExceptionIsNotTransient() {
        assertFalse(TradingLoopService.isTransient(new NullPointerException("oops")));
    }
}
