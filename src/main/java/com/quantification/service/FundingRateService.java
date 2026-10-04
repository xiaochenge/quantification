package com.quantification.service;

import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.CurrentFundingRate;
import com.quantification.bitget.BitgetPublicClient.FundingRatePoint;
import com.quantification.entity.FundingRateCurrent;
import com.quantification.entity.FundingRateHistory;
import com.quantification.entity.WatchCoin;
import com.quantification.mapper.FundingRateCurrentMapper;
import com.quantification.mapper.FundingRateHistoryMapper;
import com.quantification.mapper.WatchCoinMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 资金费率采集服务。
 *
 * <p>职责：调用 Bitget 公共接口，把历史资金费率和实时资金费率写进数据库。
 * 两个入口：
 * <ul>
 *   <li>{@link #collectHistory()} —— 回补最近 90 天的历史费率，幂等，重复跑不会产生重复行</li>
 *   <li>{@link #collectCurrent()} —— 刷新每个币的最新费率（每个币一行，覆盖写）</li>
 * </ul>
 * 另有 {@code @Scheduled} 方法按固定间隔自动跑（间隔在 application.yml 的 collector 段配置）。
 */
@Service
public class FundingRateService {

    private static final Logger log = LoggerFactory.getLogger(FundingRateService.class);

    /** 时间统一按交易所所在时区口径处理，避免本地时区漂移。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 历史接口单页最大条数，官方限制为 100。 */
    private static final int PAGE_SIZE = 100;

    /**
     * 翻页的安全上限。
     *
     * <p>接口只返回最近 90 天；结算周期最短的币是 1 小时，90 天最多 90×24=2160 条，
     * 即 22 页，这里留 30 页兜底。增量采集会在"碰到库里已有结算点"时提前停，
     * 正常不会真的翻这么多页，这个上限只是防止接口异常时无限循环。
     */
    private static final int MAX_PAGES = 30;

    private final BitgetPublicClient bitget;
    private final WatchCoinMapper watchCoinMapper;
    private final FundingRateHistoryMapper historyMapper;
    private final FundingRateCurrentMapper currentMapper;

    public FundingRateService(BitgetPublicClient bitget,
                              WatchCoinMapper watchCoinMapper,
                              FundingRateHistoryMapper historyMapper,
                              FundingRateCurrentMapper currentMapper) {
        this.bitget = bitget;
        this.watchCoinMapper = watchCoinMapper;
        this.historyMapper = historyMapper;
        this.currentMapper = currentMapper;
    }

    /** 定时回补历史费率（默认每 6 小时一次，见 application.yml）。 */
    @Scheduled(initialDelayString = "${collector.history-initial-delay-ms:8000}",
               fixedDelayString = "${collector.history-interval-ms:21600000}")
    public void scheduledHistory() {
        collectHistory();
    }

    /** 定时刷新实时费率（默认每 10 分钟一次，见 application.yml）。 */
    @Scheduled(initialDelayString = "${collector.current-initial-delay-ms:5000}",
               fixedDelayString = "${collector.current-interval-ms:600000}")
    public void scheduledCurrent() {
        collectCurrent();
    }

    /**
     * 回补所有启用币种的历史资金费率。
     *
     * <p>单个币种失败不影响其他币种，失败会记日志并在返回值里体现。
     *
     * @return 本次采集结果（币种数 / 写入行数 / 失败币种数）
     */
    public CollectResult collectHistory() {
        List<WatchCoin> coins = watchCoinMapper.findEnabled();
        int saved = 0;
        int failed = 0;
        for (WatchCoin coin : coins) {
            try {
                saved += collectHistoryFor(coin.getFuturesSymbol(), coin.getFuturesCategory());
            } catch (Exception e) {
                failed++;
                log.warn("历史资金费率采集失败 symbol={}", coin.getFuturesSymbol(), e);
            }
        }
        CollectResult result = new CollectResult(coins.size(), saved, failed);
        log.info("历史资金费率采集完成：{}", result);
        return result;
    }

    /**
     * 采集单个币种的历史费率。
     *
     * <p>核心：<b>从新到旧逐页拉取，一旦碰到库里已有的结算点就停</b>。这样：
     * <ul>
     *   <li>首次采集（库里没有该币）会一路翻到接口的 90 天边界；</li>
     *   <li>暂停再启动时，只把"库中最新的结算点之后"这段补回来，<b>中间不断档</b>，也不重复拉旧数据；</li>
     *   <li>库里的老数据原样保留——接口只给 90 天，但本地表从不删除，最早那批 90 天记录继续存在。</li>
     * </ul>
     *
     * <p>幂等靠 {@code (symbol, funding_time)} 唯一键：重叠部分即使重复插入也会被跳过。
     *
     * @param symbol   永续交易对
     * @param category 产品线
     * @return 本次新写入的行数（已存在的会被跳过，不计入）
     */
    private int collectHistoryFor(String symbol, String category) {
        int saved = 0;
        LocalDateTime latestStored = historyMapper.latestFundingTime(symbol);
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<FundingRatePoint> points = bitget.historyFundingRate(category, symbol, PAGE_SIZE, page);
            if (points.isEmpty()) {
                break;
            }
            boolean reachedExisting = false;
            for (FundingRatePoint point : points) {
                FundingRateHistory row = toHistoryEntity(symbol, category, point);
                // 接口按结算时间从新到旧返回：一旦碰到库里已有（或更早）的结算点，
                // 说明后面的全是老数据，停止翻页。
                if (latestStored != null && row.getFundingTime() != null
                        && !row.getFundingTime().isAfter(latestStored)) {
                    reachedExisting = true;
                    break;
                }
                saved += historyMapper.insertIgnore(row);
            }
            if (reachedExisting || points.size() < PAGE_SIZE) {
                break;
            }
        }
        return saved;
    }

    /**
     * 刷新所有启用币种的实时资金费率。
     *
     * @return 本次采集结果（币种数 / 写入行数 / 失败币种数）
     */
    public CollectResult collectCurrent() {
        List<WatchCoin> coins = watchCoinMapper.findEnabled();
        int saved = 0;
        int failed = 0;
        for (WatchCoin coin : coins) {
            try {
                saved += collectCurrentFor(coin.getFuturesSymbol(), coin.getFuturesCategory());
            } catch (Exception e) {
                failed++;
                log.warn("实时资金费率采集失败 symbol={}", coin.getFuturesSymbol(), e);
            }
        }
        CollectResult result = new CollectResult(coins.size(), saved, failed);
        log.info("实时资金费率采集完成：{}", result);
        return result;
    }

    /**
     * 采集单个币种的实时费率并覆盖写入。
     *
     * @param symbol   永续交易对
     * @param category 产品线
     * @return 写入行数
     */
    private int collectCurrentFor(String symbol, String category) {
        int saved = 0;
        for (CurrentFundingRate point : bitget.currentFundingRate(category, symbol)) {
            currentMapper.upsert(toCurrentEntity(symbol, category, point));
            saved++;
        }
        return saved;
    }

    /** 把接口返回的一条历史费率转换成实体。 */
    private FundingRateHistory toHistoryEntity(String symbol, String category, FundingRatePoint point) {
        FundingRateHistory row = new FundingRateHistory();
        row.setSymbol(symbol);
        row.setCategory(category);
        row.setFundingRate(toDecimal(point.fundingRate()));
        row.setFundingTime(toLocalDateTime(point.fundingRateTimestamp()));
        return row;
    }

    /** 把接口返回的一条实时费率转换成实体。 */
    private FundingRateCurrent toCurrentEntity(String symbol, String category, CurrentFundingRate point) {
        FundingRateCurrent row = new FundingRateCurrent();
        row.setSymbol(symbol);
        row.setCategory(category);
        row.setFundingRate(toDecimal(point.fundingRate()));
        row.setFundingRateInterval(toInteger(point.fundingRateInterval()));
        row.setNextUpdateTime(toLocalDateTime(point.nextUpdate()));
        row.setMinFundingRate(toDecimal(point.minFundingRate()));
        row.setMaxFundingRate(toDecimal(point.maxFundingRate()));
        row.setSourceTime(LocalDateTime.now(ZONE));
        return row;
    }

    /** 字符串转金额，空值与字符串 "null" 一律当空处理。 */
    private static BigDecimal toDecimal(String value) {
        return isBlank(value) ? null : new BigDecimal(value);
    }

    /** 字符串转整数，空值与字符串 "null" 一律当空处理。 */
    private static Integer toInteger(String value) {
        return isBlank(value) ? null : Integer.valueOf(value);
    }

    /**
     * 交易所返回的毫秒时间戳转成可读时间（Asia/Shanghai）。
     *
     * <p>数据库里存的是 DATETIME 而不是时间戳，所以转换只在这个边界做一次。
     */
    private static LocalDateTime toLocalDateTime(String epochMillis) {
        return isBlank(epochMillis)
                ? null
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(epochMillis)), ZONE);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || "null".equals(value);
    }

    /**
     * 采集结果。
     *
     * @param coins  参与采集的币种数
     * @param saved  写入（或新插入）的行数
     * @param failed 采集失败的币种数
     */
    public record CollectResult(int coins, int saved, int failed) {
    }
}
