package com.quantification.service;

import com.quantification.bitget.BitgetPrivateClient;
import com.quantification.bitget.BitgetPrivateClient.FeeRateItem;
import com.quantification.bitget.BitgetPublicClient;
import com.quantification.entity.FeeRate;
import com.quantification.entity.WatchCoin;
import com.quantification.mapper.FeeRateMapper;
import com.quantification.mapper.WatchCoinMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 手续费率采集服务。
 *
 * <p>用私有接口 {@code /api/v3/account/all-fee-rate} 拿<b>账户真实费率</b>（含 VIP 折扣），
 * 只保存监控篮子里的币种，避免把上千条无关交易对写进库。
 *
 * <p>实测差异（2026-09-23）：公共接口给的是标准费率（合约吃单 0.06%），
 * 私有接口给的是账户实际费率（合约吃单 0.0375%），后者才是成本计算的正确输入。
 */
@Service
public class FeeRateService {

    private static final Logger log = LoggerFactory.getLogger(FeeRateService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final BitgetPrivateClient privateClient;
    private final WatchCoinMapper watchCoinMapper;
    private final FeeRateMapper feeRateMapper;

    public FeeRateService(BitgetPrivateClient privateClient,
                          WatchCoinMapper watchCoinMapper,
                          FeeRateMapper feeRateMapper) {
        this.privateClient = privateClient;
        this.watchCoinMapper = watchCoinMapper;
        this.feeRateMapper = feeRateMapper;
    }

    /** 定时采集费率（默认每天一次，见 application.yml 的 collector 段）。 */
    @Scheduled(initialDelayString = "${collector.fee-rate-initial-delay-ms:12000}",
               fixedDelayString = "${collector.fee-rate-interval-ms:86400000}")
    public void scheduledCollect() {
        collect();
    }

    /**
     * 采集一次费率并写入快照表。
     *
     * @return 写入行数；未配置 API Key 时返回 0
     */
    public int collect() {
        if (!privateClient.isConfigured()) {
            log.warn("未配置 Bitget API Key，跳过分手续费率采集");
            return 0;
        }
        Set<String> basket = new HashSet<>();
        for (WatchCoin coin : watchCoinMapper.findEnabled()) {
            basket.add(coin.getSpotSymbol());
            basket.add(coin.getFuturesSymbol());
        }

        int saved = 0;
        saved += saveCategory(BitgetPublicClient.SPOT, basket);
        saved += saveCategory(BitgetPublicClient.USDT_FUTURES, basket);
        log.info("手续费率采集完成，写入 {} 行", saved);
        return saved;
    }

    private int saveCategory(String category, Set<String> basket) {
        int saved = 0;
        for (FeeRateItem item : privateClient.allFeeRates(category)) {
            if (!basket.contains(item.symbol())) {
                continue;
            }
            FeeRate row = new FeeRate();
            row.setSymbol(item.symbol());
            row.setCategory(category);
            row.setMakerFeeRate(new BigDecimal(item.makerFeeRate()));
            row.setTakerFeeRate(new BigDecimal(item.takerFeeRate()));
            row.setSourceTime(LocalDateTime.now(ZONE));
            saved += feeRateMapper.insert(row);
        }
        return saved;
    }
}
