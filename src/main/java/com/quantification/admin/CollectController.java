package com.quantification.admin;

import com.quantification.service.FundingRateService;
import com.quantification.service.FundingRateService.CollectResult;
import com.quantification.service.FeeRateService;
import com.quantification.service.WatchCoinService;
import com.quantification.service.WatchCoinService.SyncResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集的手动触发入口（调试用）。
 *
 * <p>正式采集由 {@link FundingRateService} 上的定时任务负责；这里的接口只是方便
 * 本地验证和排查问题时立刻触发一次，不参与常规运行。
 *
 * <p>后台一期是只读的，所以这里只提供"触发采集"，不提供任何交易操作。
 */
@RestController
@RequestMapping("/api/admin/collect")
public class CollectController {

    /** 采集服务。 */
    private final FundingRateService fundingRateService;

    /** 监控篮子同步服务。 */
    private final WatchCoinService watchCoinService;

    /** 手续费率采集服务。 */
    private final FeeRateService feeRateService;

    /**
     * @param fundingRateService 资金费率采集服务
     * @param watchCoinService   监控篮子同步服务
     * @param feeRateService     手续费率采集服务
     */
    public CollectController(FundingRateService fundingRateService,
                             WatchCoinService watchCoinService,
                             FeeRateService feeRateService) {
        this.fundingRateService = fundingRateService;
        this.watchCoinService = watchCoinService;
        this.feeRateService = feeRateService;
    }

    /**
     * 立刻回补一次历史资金费率。
     *
     * @return 采集结果（币种数 / 写入行数 / 失败币种数）
     */
    @PostMapping("/funding-rate/history")
    public CollectResult collectHistory() {
        return fundingRateService.collectHistory();
    }

    /**
     * 立刻刷新一次实时资金费率。
     *
     * @return 采集结果（币种数 / 写入行数 / 失败币种数）
     */
    @PostMapping("/funding-rate/current")
    public CollectResult collectCurrent() {
        return fundingRateService.collectCurrent();
    }

    /**
     * 立刻同步一次监控篮子（把所有"现货 + 永续都有"的币纳入）。
     *
     * @return 同步结果
     */
    @PostMapping("/watch-coin/sync")
    public SyncResult syncWatchCoin() {
        return watchCoinService.syncEligibleCoins();
    }

    /**
     * 立刻采集一次账户真实手续费率（需要 API Key）。
     *
     * @return 写入的行数
     */
    @PostMapping("/fee-rate")
    public int collectFeeRate() {
        return feeRateService.collect();
    }
}
