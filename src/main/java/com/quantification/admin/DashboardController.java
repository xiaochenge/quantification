package com.quantification.admin;

import com.quantification.entity.AccountBalanceSnapshot;
import com.quantification.entity.EventLog;
import com.quantification.entity.FundingIncome;
import com.quantification.entity.FundingRateHistory;
import com.quantification.entity.HostMetric;
import com.quantification.entity.TradeFill;
import com.quantification.entity.TradeOrder;
import com.quantification.service.DashboardService;
import com.quantification.service.DashboardService.CandidateView;
import com.quantification.service.DashboardService.Overview;
import com.quantification.service.DashboardService.Pnl;
import com.quantification.service.DashboardService.ParamView;
import com.quantification.service.DashboardService.PositionView;
import com.quantification.service.SimulationService;
import com.quantification.service.HostMonitorService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理后台的只读数据接口（模块 10，本地免登录）。
 *
 * <p>前台是 Vue3 + Element Plus + ECharts，通过这里轮询取数（5~10 秒一次），
 * 全部 GET、只读，不做任何交易或配置改动。
 */
@RestController
@RequestMapping("/api/admin")
public class DashboardController {

    /** 数据装配层。 */
    private final DashboardService dashboardService;

    /** 模拟盘账务（成交/资金费/净值曲线复用它的查询）。 */
    private final SimulationService simulationService;

    /** 主机资源监控。 */
    private final HostMonitorService hostMonitorService;

    public DashboardController(DashboardService dashboardService,
                               SimulationService simulationService,
                               HostMonitorService hostMonitorService) {
        this.dashboardService = dashboardService;
        this.simulationService = simulationService;
        this.hostMonitorService = hostMonitorService;
    }

    /** 总览。 */
    @GetMapping("/overview")
    public Overview overview() {
        return dashboardService.overview();
    }

    /** 持仓。 */
    @GetMapping("/positions")
    public List<PositionView> positions() {
        return dashboardService.positions();
    }

    /** 候选池。 */
    @GetMapping("/candidates")
    public List<CandidateView> candidates() {
        return dashboardService.candidates();
    }

    /** 当前参数。 */
    @GetMapping("/params")
    public List<ParamView> params() {
        return dashboardService.params();
    }

    /** 事件日志（异常与告警）。 */
    @GetMapping("/events")
    public List<EventLog> events(@RequestParam(defaultValue = "200") int limit) {
        return dashboardService.events(limit);
    }

    /** 单个币的历史资金费率（候选池里的单币曲线）。 */
    @GetMapping("/funding-rate-history")
    public List<FundingRateHistory> fundingRateHistory(@RequestParam String symbol,
                                                       @RequestParam(defaultValue = "200") int limit) {
        return dashboardService.fundingRateHistory(symbol, limit);
    }

    /** 订单。 */
    @GetMapping("/orders")
    public List<TradeOrder> orders(@RequestParam(defaultValue = "100") int limit) {
        return simulationService.recentOrders(limit);
    }

    /** 成交明细。 */
    @GetMapping("/fills")
    public List<TradeFill> fills(@RequestParam(defaultValue = "100") int limit) {
        return simulationService.recentFills(limit);
    }

    /** 资金费入账。 */
    @GetMapping("/funding-income")
    public List<FundingIncome> fundingIncome(@RequestParam(defaultValue = "100") int limit) {
        return simulationService.recentFundingIncome(limit);
    }

    /** 净值曲线。 */
    @GetMapping("/equity-curve")
    public List<AccountBalanceSnapshot> equityCurve(@RequestParam(defaultValue = "500") int limit) {
        return simulationService.equityCurve(limit);
    }

    /** 盈亏核算（损耗分解）。 */
    @GetMapping("/pnl")
    public Pnl pnl() {
        return dashboardService.pnl();
    }

    /** 机器当前资源使用情况（CPU / 内存 / 磁盘 / 负载）。 */
    @GetMapping("/host")
    public HostMetric host() {
        return hostMonitorService.current();
    }

    /** 机器资源历史曲线（倒序）。 */
    @GetMapping("/host-history")
    public List<HostMetric> hostHistory(@RequestParam(defaultValue = "1000") int limit) {
        return hostMonitorService.recent(limit);
    }
}
