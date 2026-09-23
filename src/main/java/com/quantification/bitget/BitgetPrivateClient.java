package com.quantification.bitget;

import com.quantification.bitget.BitgetPublicClient.BitgetResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Bitget 私有接口客户端（需要 API Key），支持实盘与模拟盘两套密钥。
 *
 * <p><b>签名规则</b>（官方文档）：把 {@code timestamp + METHOD + requestPath + "?" + queryString + body}
 * 拼成一个字符串，用 SecretKey 做 HMAC-SHA256，再 Base64 编码，放进 ACCESS-SIGN 头。
 * 请求头还需要 ACCESS-KEY、ACCESS-TIMESTAMP（毫秒）、ACCESS-PASSPHRASE。
 *
 * <p><b>实盘 / 模拟盘切换</b>：由配置 {@code bitget.paptrading} 控制。
 * <ul>
 *   <li>{@code false}（默认）：使用 api-key / secret-key / passphrase，走实盘；</li>
 *   <li>{@code true}：使用 demo-api-key / demo-secret-key / demo-passphrase，并给每个请求加上
 *       {@code paptrading: 1} 请求头，走官方模拟盘（虚拟资金，真实行情）。</li>
 * </ul>
 * 注意官方文档明确：用实盘 key 加 {@code paptrading: 1} 头<b>不会</b>切到模拟盘（已实测确认），
 * 必须使用模拟盘专用 key，所以两套密钥必须分开配置。
 *
 * <p>密钥全部来自仓库外的 {@code ~/.quantification/application-local.yml}，
 * 该文件不提交、不打包（见 AGENTS.md 的安全红线）。没配密钥时 {@link #isConfigured()} 返回 false。
 */
@Component
public class BitgetPrivateClient {

    private static final Logger log = LoggerFactory.getLogger(BitgetPrivateClient.class);

    /** Bitget 成功响应的业务码。 */
    private static final String OK = "00000";

    private final RestClient client;

    /** 是否走模拟盘。 */
    private final boolean paptrading;

    /** 实盘密钥。 */
    private final String realApiKey;
    private final String realSecretKey;
    private final String realPassphrase;

    /** 模拟盘密钥。 */
    private final String demoApiKey;
    private final String demoSecretKey;
    private final String demoPassphrase;

    public BitgetPrivateClient(@Value("${bitget.base-url:https://api.bitget.com}") String baseUrl,
                               @Value("${bitget.paptrading:false}") boolean paptrading,
                               @Value("${bitget.api-key:}") String realApiKey,
                               @Value("${bitget.secret-key:}") String realSecretKey,
                               @Value("${bitget.passphrase:}") String realPassphrase,
                               @Value("${bitget.demo-api-key:}") String demoApiKey,
                               @Value("${bitget.demo-secret-key:}") String demoSecretKey,
                               @Value("${bitget.demo-passphrase:}") String demoPassphrase) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.paptrading = paptrading;
        this.realApiKey = realApiKey;
        this.realSecretKey = realSecretKey;
        this.realPassphrase = realPassphrase;
        this.demoApiKey = demoApiKey;
        this.demoSecretKey = demoSecretKey;
        this.demoPassphrase = demoPassphrase;

        // 启动时明确打出当前模式，避免"以为在模拟盘、实际在实盘"
        if (paptrading) {
            log.info("Bitget 运行在【模拟盘】模式（paptrading=1，虚拟资金），不会动到真实资金");
        } else {
            log.info("Bitget 运行在【实盘】模式，下单会真实成交");
        }
    }

    /**
     * 是否已经配置了当前模式所需的 API Key。
     *
     * @return 模拟盘模式看 demo 密钥，实盘模式看实盘密钥，三个都非空才返回 true
     */
    public boolean isConfigured() {
        return !apiKey().isBlank() && !secretKey().isBlank() && !passphrase().isBlank();
    }

    /**
     * 查询某个产品线下所有交易对手续费率（含 VIP 折扣）。
     *
     * @param category 产品线，如 SPOT 或 USDT-FUTURES
     * @return 费率列表；未配置密钥时返回空列表
     * @throws BitgetApiException 接口返回非成功码或响应结构异常
     */
    public List<FeeRateItem> allFeeRates(String category) {
        if (!isConfigured()) {
            log.warn("当前模式（{}）未配置 Bitget API Key，跳过手续费率查询", paptrading ? "模拟盘" : "实盘");
            return List.of();
        }
        String path = "/api/v3/account/all-fee-rate";
        String query = "category=" + category;
        String timestamp = String.valueOf(System.currentTimeMillis());

        RestClient.RequestHeadersSpec<?> spec = client.get()
                .uri(uri -> uri.path(path).queryParam("category", category).build());
        if (paptrading) {
            spec = spec.header("paptrading", "1");
        }

        BitgetResponse<List<FeeRateItem>> resp = spec
                .header("ACCESS-KEY", apiKey())
                .header("ACCESS-SIGN", sign(timestamp, "GET", path, query))
                .header("ACCESS-TIMESTAMP", timestamp)
                .header("ACCESS-PASSPHRASE", passphrase())
                .header("Content-Type", "application/json")
                .header("locale", "zh-CN")
                .retrieve()
                .body(new ParameterizedTypeReference<BitgetResponse<List<FeeRateItem>>>() {});
        if (resp == null || !OK.equals(resp.code()) || resp.data() == null) {
            throw new BitgetApiException("all-fee-rate 调用失败: "
                    + (resp == null ? "无响应" : resp.code() + " " + resp.msg()));
        }
        return resp.data();
    }

    private String apiKey() {
        return paptrading ? demoApiKey : realApiKey;
    }

    private String secretKey() {
        return paptrading ? demoSecretKey : realSecretKey;
    }

    private String passphrase() {
        return paptrading ? demoPassphrase : realPassphrase;
    }

    /** 按官方规则生成签名：HMAC-SHA256(timestamp + METHOD + path + "?" + query) 再 Base64。 */
    private String sign(String timestamp, String method, String requestPath, String queryString) {
        String preHash = timestamp + method + requestPath
                + (queryString == null || queryString.isEmpty() ? "" : "?" + queryString);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(preHash.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BitgetApiException("生成签名失败: " + e.getMessage());
        }
    }

    /**
     * 手续费率的一条记录。
     *
     * @param symbol       交易对
     * @param makerFeeRate 挂单费率（小数）
     * @param takerFeeRate 吃单费率（小数）
     */
    public record FeeRateItem(String symbol, String makerFeeRate, String takerFeeRate) {
    }
}
