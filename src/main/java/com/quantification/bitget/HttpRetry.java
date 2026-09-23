package com.quantification.bitget;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

/**
 * 网络抖动的重试工具。
 *
 * <p>为什么需要：调用交易所时偶发 <b>TLS 握手被重置 / 连接重置</b>（实测日志：
 * {@code SSLHandshakeException: Remote host terminated the handshake} → {@code Connection reset}）。
 * 这类是瞬时故障，重试即可，但如果不重试，整批采集或下单就会失败。
 *
 * <p>策略：
 * <ul>
 *   <li>只对<b>网络 / IO 异常</b>重试（{@link ResourceAccessException}，含超时、握手失败、连接重置）；</li>
 *   <li>参数错、签名错、余额不足这类<b>业务错误不重试</b>——重试也没用，直接抛给上层；</li>
 *   <li>间隔按 1s → 2s → 4s 指数退避，避免把对方打得更狠。</li>
 * </ul>
 */
public final class HttpRetry {

    private static final Logger log = LoggerFactory.getLogger(HttpRetry.class);

    /** 最大尝试次数（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 首次重试等待（毫秒），之后翻倍。 */
    private static final long FIRST_BACKOFF_MS = 1000L;

    private HttpRetry() {
    }

    /**
     * 执行一次可能受网络抖动影响的操作，失败则退避重试。
     *
     * @param action 要执行的操作
     * @param what   操作描述（只用于日志）
     * @param <T>    返回类型
     * @return 操作结果
     */
    public static <T> T call(Supplier<T> action, String what) {
        ResourceAccessException lastError = null;
        long backoff = FIRST_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return action.get();
            } catch (ResourceAccessException e) {
                lastError = e;
                if (attempt == MAX_ATTEMPTS) {
                    break;
                }
                log.warn("{} 第 {}/{} 次失败（网络抖动），{}ms 后重试：{}",
                        what, attempt, MAX_ATTEMPTS, backoff, e.getMessage());
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                backoff *= 2;
            }
        }
        log.error("{} 重试 {} 次仍失败，放弃本次调用", what, MAX_ATTEMPTS);
        throw lastError;
    }
}
