package com.quantification.bitget;

/**
 * 调用 Bitget 接口失败时抛出。
 *
 * <p>触发场景：HTTP 调用异常、返回的业务码不是成功码（非 00000）、或响应结构与预期不符。
 * 上层（service）捕获它来决定"这个币这次采集失败、跳过、继续处理下一个币"。
 */
public class BitgetApiException extends RuntimeException {

    /**
     * @param message 失败原因，通常包含接口名与交易所返回的 code / msg
     */
    public BitgetApiException(String message) {
        super(message);
    }
}
