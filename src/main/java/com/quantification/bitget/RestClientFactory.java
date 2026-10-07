package com.quantification.bitget;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 统一构造<b>带超时</b>的 {@link RestClient}，供公共 / 私有客户端共用。
 *
 * <p><b>为什么必须设超时</b>：{@code RestClient} 默认用 JDK {@link HttpClient}，
 * 连接与读取都没有超时。实测 2026-10-06 出现过"对端连接半开、本地读永远不返回"的情况，
 * 调用线程会一直挂在 {@code CompletableFuture.get()} 上（见故障复盘：管理后台整页转圈、
 * mock 十几个小时不结算）。这类故障不会抛异常，所以 {@link HttpRetry} 也救不了。
 *
 * <p>设了超时后，同样的情况会抛 {@code ResourceAccessException}，交由 {@link HttpRetry}
 * 按 1s→2s 退避重试，最终放弃并抛给上层——<b>有界失败永远好过无限等待</b>。
 */
final class RestClientFactory {

    private RestClientFactory() {
    }

    /**
     * @param baseUrl         接口地址
     * @param connectTimeoutMs 连接超时（毫秒）
     * @param readTimeoutMs    读取超时（毫秒）
     * @return 带超时的 RestClient
     */
    static RestClient create(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
