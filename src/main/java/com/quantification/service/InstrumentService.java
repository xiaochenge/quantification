package com.quantification.service;

import com.quantification.bitget.BitgetPublicClient;
import com.quantification.bitget.BitgetPublicClient.InstrumentInfo;
import com.quantification.entity.Instrument;
import com.quantification.entity.WatchCoin;
import com.quantification.mapper.InstrumentMapper;
import com.quantification.mapper.WatchCoinMapper;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 交易对规则同步服务：把下单需要的精度 / 最小最大值 / 乘数<b>落库</b>。
 *
 * <p>为什么这么做（2026-09-23 实际亏损事故的教训）：
 * <ul>
 *   <li>下单参数（精度、最小/最大值、乘数）<b>绝不能猜</b>，只能来自交易所接口；</li>
 *   <li>也不能"下单时临时去查"——一旦接口抖动或拿到异常值，就会下出非法订单；</li>
 *   <li>正确做法：定时（默认每小时）同步进 {@code instrument} 表，下单时从库里读。</li>
 * </ul>
 *
 * <p>只同步监控篮子里的币种（现货 + 合约各一条），避免把上千个无关交易对写进库。
 */
@Service
public class InstrumentService {

    private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

    private final BitgetPublicClient bitget;
    private final WatchCoinMapper watchCoinMapper;
    private final InstrumentMapper instrumentMapper;

    public InstrumentService(BitgetPublicClient bitget,
                             WatchCoinMapper watchCoinMapper,
                             InstrumentMapper instrumentMapper) {
        this.bitget = bitget;
        this.watchCoinMapper = watchCoinMapper;
        this.instrumentMapper = instrumentMapper;
    }

    /** 定时同步交易对规则（默认启动 10 秒后一次，之后每小时一次）。 */
    @Scheduled(initialDelayString = "${instrument.sync-initial-delay-ms:10000}",
               fixedDelayString = "${instrument.sync-interval-ms:3600000}")
    public void scheduledSync() {
        sync();
    }

    /**
     * 同步一次篮子内所有交易对的规则。
     *
     * @return 写入 / 更新的行数
     */
    public int sync() {
        Set<String> symbols = new LinkedHashSet<>();
        for (WatchCoin coin : watchCoinMapper.findEnabled()) {
            symbols.add(coin.getSpotSymbol());
            symbols.add(coin.getFuturesSymbol());
        }
        int saved = 0;
        saved += upsertCategory(BitgetPublicClient.SPOT, symbols);
        saved += upsertCategory(BitgetPublicClient.USDT_FUTURES, symbols);
        log.info("交易对规则同步完成，写入 {} 行", saved);
        return saved;
    }

    private int upsertCategory(String category, Set<String> symbols) {
        int saved = 0;
        for (InstrumentInfo info : bitget.instruments(category)) {
            if (!symbols.contains(info.symbol())) {
                continue;
            }
            Instrument row = new Instrument();
            row.setCategory(category);
            row.setSymbol(info.symbol());
            row.setBaseCoin(info.baseCoin());
            row.setQuoteCoin(info.quoteCoin());
            row.setContractType(info.type());
            row.setMinOrderQty(decimal(info.minOrderQty()));
            row.setMaxOrderQty(decimal(info.maxOrderQty()));
            row.setPricePrecision(integer(info.pricePrecision()));
            row.setQuantityPrecision(integer(info.quantityPrecision()));
            row.setQuotePrecision(integer(info.quotePrecision()));
            row.setPriceMultiplier(decimal(info.priceMultiplier()));
            row.setQuantityMultiplier(decimal(info.quantityMultiplier()));
            saved += instrumentMapper.upsert(row);
        }
        return saved;
    }

    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private static Integer integer(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
}
