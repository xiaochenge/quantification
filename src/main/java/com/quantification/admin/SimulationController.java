package com.quantification.admin;

import com.quantification.entity.AccountBalanceSnapshot;
import com.quantification.entity.FundingIncome;
import com.quantification.entity.TradeFill;
import com.quantification.entity.TradeOrder;
import com.quantification.service.SimulationService;
import com.quantification.service.SimulationService.SimulationStatus;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自建 mock 的只读查询接口（模块 9 的验收窗口）。
 *
 * <p>看三样东西：
 * <ul>
 *   <li>{@code GET /api/admin/simulation/status} —— 模拟账户现金、持仓、累计资金费、手续费、滚动年化；</li>
 *   <li>{@code GET /api/admin/simulation/equity-curve} —— 模拟净值曲线（每小时一条）；</li>
 *   <li>另外两个 POST 只是调试用的手动触发（记一次净值 / 结一次资金费），与采集接口同一风格。</li>
 * </ul>
 * 与后台的定位一致：<b>只读为主</b>，不做任何交易操作。
 */
@RestController
@RequestMapping("/api/admin/simulation")
public class SimulationController {

    /** 模拟盘账务与查询服务。 */
    private final SimulationService simulationService;

    /**
     * @param simulationService 模拟盘账务与查询服务
     */
    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    /**
     * 查模拟盘当前状态。
     *
     * @return 现金 / 净值 / 持仓 / 资金费 / 手续费 / 滚动年化
     */
    @GetMapping("/status")
    public SimulationStatus status() {
        return simulationService.status();
    }

    /**
     * 查模拟净值曲线。
     *
     * @param limit 取多少条（默认 200 条，按时间倒序）
     * @return 净值快照列表
     */
    @GetMapping("/equity-curve")
    public List<AccountBalanceSnapshot> equityCurve(@RequestParam(defaultValue = "200") int limit) {
        return simulationService.equityCurve(limit);
    }

    /**
     * 立刻记一条模拟净值快照（调试用）。
     *
     * @return 写入的快照；当前不是自建 mock 模式时返回 null
     */
    @PostMapping("/snapshot")
    public AccountBalanceSnapshot snapshot() {
        return simulationService.snapshot();
    }

    /**
     * 立刻结算一次资金费（调试用）。
     *
     * @return 说明文本
     */
    @PostMapping("/settle-funding")
    public String settleFunding() {
        return simulationService.settleFundingNow();
    }

    /**
     * 查模拟成交明细（按时间倒序）。
     *
     * @param limit 取多少条（默认 100）
     * @return 成交明细列表
     */
    @GetMapping("/fills")
    public List<TradeFill> fills(@RequestParam(defaultValue = "100") int limit) {
        return simulationService.recentFills(limit);
    }

    /**
     * 查模拟资金费入账明细（按时间倒序）。
     *
     * @param limit 取多少条（默认 100）
     * @return 资金费入账列表
     */
    @GetMapping("/funding-income")
    public List<FundingIncome> fundingIncome(@RequestParam(defaultValue = "100") int limit) {
        return simulationService.recentFundingIncome(limit);
    }

    /**
     * 查模拟订单（按时间倒序）。
     *
     * @param limit 取多少条（默认 100）
     * @return 订单列表
     */
    @GetMapping("/orders")
    public List<TradeOrder> orders(@RequestParam(defaultValue = "100") int limit) {
        return simulationService.recentOrders(limit);
    }
}
