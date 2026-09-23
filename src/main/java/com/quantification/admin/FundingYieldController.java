package com.quantification.admin;

import com.quantification.service.FundingYieldService;
import com.quantification.service.FundingYieldService.FundingYield;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资金费率收益查询接口（只读）。
 *
 * <p>返回每个币种在统计窗口内的毛年化、换仓成本年化和净年化，按净年化从高到低排序。
 * 参数（窗口天数、轮换天数、费率）在 application.yml 的 funding-yield 段配置。
 */
@RestController
@RequestMapping("/api/admin/funding-yield")
public class FundingYieldController {

    /** 收益计算服务。 */
    private final FundingYieldService fundingYieldService;

    /**
     * @param fundingYieldService 收益计算服务
     */
    public FundingYieldController(FundingYieldService fundingYieldService) {
        this.fundingYieldService = fundingYieldService;
    }

    /**
     * 查询各币种收益率。
     *
     * @return 按净年化降序排列的收益列表
     */
    @GetMapping
    public List<FundingYield> list() {
        return fundingYieldService.calculate();
    }
}
