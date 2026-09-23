#!/usr/bin/env python3
"""
资金费率套现策略回测（模块 3 规则验证用）。

用法:
    python3 scripts/backtest-funding.py daily.tsv turnover.tsv [settlements.tsv]

输入文件（都由 MySQL 导出，制表符分隔）:
    daily.tsv       每日资金费汇总: symbol, date(YYYY-MM-DD), day_rate_sum
    turnover.tsv    24 小时成交额: symbol, turnover24h
    settlements.tsv 逐笔结算费率（可选）: symbol, datetime(YYYY-MM-DD HH:MM:SS), rate
                    有这个文件才能启用"恢复轮数"过滤

规则（详见 docs/phase-2-strategy-design.md 模块 3）:
    * 决策口径: 最近 WINDOW_DAYS 天的综合费率（正负累加）折算成年化，再减去年化换仓成本
    * 建仓门槛: 净年化 >= ENTRY_NET
    * 恢复轮数过滤（可选，RECOVERY_FILTER=1 开启）: 当前"连续为正"的结算轮数，
      必须超过该币历史"从负费率恢复到正费率"的平均轮数，才允许建仓
    * 换仓门槛: 候选净年化 - 当前最差持仓净年化 >= SWITCH_GAP
    * 最多持有 MAX_HOLDINGS 个币，按 24h 成交额分配权重；不足则少持
    * 换仓按仓位变动比例收一次换仓成本

说明: 决策按"天"评估（简化），收益按天累加；真实策略按小时评估。
"""

import bisect
import os
import sys
from collections import defaultdict

# ---------- 可调参数（全部可用环境变量覆盖，便于做敏感性分析）----------
WINDOW_DAYS = int(os.environ.get("WINDOW_DAYS", 10))              # 综合费率窗口（天）
MIN_TURNOVER = int(os.environ.get("MIN_TURNOVER", 5_000_000))     # 目标币种池: 24h 成交额下限
ENTRY_NET = float(os.environ.get("ENTRY_NET", 0.05))              # 建仓门槛: 净年化
SWITCH_GAP = float(os.environ.get("SWITCH_GAP", 0.04))            # 换仓门槛: 比最差持仓高多少
MAX_HOLDINGS = int(os.environ.get("MAX_HOLDINGS", 3))             # 最多同时持有几个币
SPOT_TAKER_FEE = float(os.environ.get("SPOT_TAKER_FEE", 0.0006))  # 现货吃单费率（账户真实值）
PERP_TAKER_FEE = float(os.environ.get("PERP_TAKER_FEE", 0.000375))  # 合约吃单费率（账户真实值）
ROTATION_DAYS = int(os.environ.get("ROTATION_DAYS", 45))          # 换仓成本年化用的轮换周期
RECOVERY_FILTER = os.environ.get("RECOVERY_FILTER", "0") == "1"   # 是否启用恢复轮数过滤

# 一次完整换仓（平旧仓 + 建新仓）的成本系数
ROTATION_COST = 2 * (SPOT_TAKER_FEE + PERP_TAKER_FEE)
# 年化换仓成本
ANNUAL_COST = ROTATION_COST * 365 / ROTATION_DAYS


def load_daily(path):
    """读取每日资金费，返回 symbol -> {date: rate} 与排序后的日期列表。"""
    by_symbol = defaultdict(dict)
    dates = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            by_symbol[parts[0]][parts[1]] = float(parts[2])
            dates.add(parts[1])
    return by_symbol, sorted(dates)


def load_turnover(path, min_turnover):
    """读取成交额，返回 symbol -> turnover（只保留达到门槛的）。"""
    turnover = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2:
                continue
            try:
                value = float(parts[1])
            except ValueError:
                continue
            if value >= min_turnover:
                turnover[parts[0]] = value
    return turnover


def load_settlements(path):
    """读取逐笔结算费率，返回 symbol -> [(date, rate), ...]（按时间升序）。"""
    data = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            data[parts[0]].append((parts[1][:10], float(parts[2])))
    for rows in data.values():
        rows.sort()
    return data


def precompute_recovery(settlements):
    """
    为每个币预计算两条序列：
      streak[i]  到第 i 笔结算为止，连续为正的轮数
      avg[i]     到第 i 笔结算为止，历史"从负恢复到正"平均需要多少轮（没有负费率时为 None）
    """
    result = {}
    for symbol, rows in settlements.items():
        dates = [row[0] for row in rows]
        rates = [row[1] for row in rows]
        count = len(rates)

        streak = [0] * count
        run = 0
        for i in range(count):
            run = run + 1 if rates[i] > 0 else 0
            streak[i] = run

        # 每笔负费率距离"下一次转正"相差几轮
        recovery = [None] * count
        next_positive = None
        for i in range(count - 1, -1, -1):
            if rates[i] > 0:
                next_positive = i
            elif next_positive is not None:
                recovery[i] = next_positive - i

        # 前缀平均（只用决策日之前的数据，避免未来信息）
        average = [None] * count
        total, seen = 0, 0
        for i in range(count):
            if recovery[i] is not None:
                total += recovery[i]
                seen += 1
            average[i] = (total / seen) if seen else None

        result[symbol] = (dates, streak, average)
    return result


def recovery_ok(recovery, symbol, day):
    """恢复轮数过滤：当前连续为正的轮数必须超过历史平均恢复轮数。"""
    item = recovery.get(symbol)
    if item is None:
        return True                     # 没有逐笔数据就不拦
    dates, streak, average = item
    index = bisect.bisect_left(dates, day) - 1
    if index < 0:
        return False                    # 决策日之前还没有数据，不建仓
    avg = average[index]
    if avg is None:
        return True                     # 历史上还没出现过负费率，不拦
    return streak[index] > avg


def trailing_net(by_symbol, dates, index, universe):
    """算出这一天各币的净年化（综合费率年化 - 年化换仓成本）。"""
    window = dates[max(0, index - WINDOW_DAYS + 1): index + 1]
    metrics = {}
    for symbol, rates in by_symbol.items():
        if symbol not in universe:
            continue
        values = [rates[d] for d in window if d in rates]
        if len(values) < WINDOW_DAYS:
            continue                    # 数据不足一个完整窗口就不参与
        metrics[symbol] = sum(values) * 365 / WINDOW_DAYS - ANNUAL_COST
    return metrics


def weights(symbols, turnover):
    """按成交额分配权重。"""
    total = sum(turnover.get(s, 0) for s in symbols)
    if total <= 0:
        return {s: 1 / len(symbols) for s in symbols}
    return {s: turnover.get(s, 0) / total for s in symbols}


def run(by_symbol, dates, turnover, recovery, hysteresis):
    """跑一遍回测。hysteresis=False 时每天都重选（用来对比换仓成本的影响）。"""
    holdings = {}
    earned = 0.0
    fee_paid = 0.0
    switch_days = 0
    cash_days = 0
    holding_counts = []
    best_nets = []
    start_index = WINDOW_DAYS       # 用"截至前一天"的窗口决策，赚当天的钱，避免前视偏差

    def passes(symbol, metrics, day):
        if metrics[symbol] < ENTRY_NET:
            return False
        return not RECOVERY_FILTER or recovery_ok(recovery, symbol, day)

    for index in range(start_index, len(dates)):
        day = dates[index]
        metrics = trailing_net(by_symbol, dates, index - 1, turnover)
        if metrics:
            best_nets.append(max(metrics.values()))

        if hysteresis:
            target = dict(holdings)
            if not target:
                top = sorted((s for s in metrics if passes(s, metrics, day)),
                             key=lambda s: -metrics[s])[:MAX_HOLDINGS]
                target = weights(top, turnover) if top else {}
            else:
                candidates = [s for s in metrics
                              if s not in target and passes(s, metrics, day)]
                candidates.sort(key=lambda s: -metrics[s])
                # 丢掉亏损的持仓，优先用更优候选补上
                for symbol in list(target):
                    if metrics.get(symbol, -9) >= 0:
                        continue
                    if candidates:
                        best = candidates.pop(0)
                        del target[symbol]
                        target[best] = turnover.get(best, 0)
                    else:
                        del target[symbol]
                # 剩余的最差持仓，若候选领先足够多则替换
                worst = min(target, key=lambda s: metrics.get(s, -9), default=None)
                if worst is not None and candidates:
                    best = candidates[0]
                    if metrics[best] >= metrics.get(worst, -9) + SWITCH_GAP:
                        del target[worst]
                        target[best] = turnover.get(best, 0)
                target = weights(list(target), turnover) if target else {}
        else:
            top = sorted((s for s in metrics if passes(s, metrics, day)),
                         key=lambda s: -metrics[s])[:MAX_HOLDINGS]
            target = weights(top, turnover) if top else {}

        # 换仓成本 = 一次完整换仓成本 × 仓位变动比例
        changed = 0.5 * sum(abs(target.get(s, 0) - holdings.get(s, 0))
                            for s in set(target) | set(holdings))
        if changed > 1e-9:
            fee = ROTATION_COST * changed
            fee_paid += fee
            earned -= fee
            switch_days += 1

        # 当天的资金费收益
        for symbol, weight in target.items():
            earned += weight * by_symbol[symbol].get(day, 0.0)

        holdings = target
        holding_counts.append(len(target))
        if not target:
            cash_days += 1

    days = len(dates) - start_index
    return {
        "days": days,
        "earned": earned,
        "fee_paid": fee_paid,
        "switch_days": switch_days,
        "annualized": earned * 365 / days if days else 0.0,
        "cash_days": cash_days,
        "avg_holdings": (sum(holding_counts) / len(holding_counts)) if holding_counts else 0.0,
        "hold_dist": {k: holding_counts.count(k) for k in sorted(set(holding_counts))},
    }


def print_result(label, result):
    print("{}:".format(label))
    print("  回测天数        : {}".format(result["days"]))
    print("  累计净收益      : {:.2%}".format(result["earned"]))
    print("  其中换仓成本    : {:.2%}（{} 次调仓）".format(result["fee_paid"], result["switch_days"]))
    print("  折合年化        : {:.2%}".format(result["annualized"]))
    print("  平均持仓币数    : {:.2f}".format(result["avg_holdings"]))
    print("  持仓分布        : {}".format(
        "  ".join("{}个币 {}天".format(k, v) for k, v in result["hold_dist"].items())))
    print()


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)

    by_symbol, dates = load_daily(sys.argv[1])
    turnover = load_turnover(sys.argv[2], MIN_TURNOVER)
    recovery = {}
    if len(sys.argv) > 3 and os.path.exists(sys.argv[3]):
        recovery = precompute_recovery(load_settlements(sys.argv[3]))

    print("参数: 窗口 {} 天 | 池门槛 {} 万 | 建仓净年化 {:.0%} | 换仓差 {} 个点 | 最多 {} 个币".format(
        WINDOW_DAYS, MIN_TURNOVER / 10000, ENTRY_NET, SWITCH_GAP * 100, MAX_HOLDINGS))
    print("换仓成本: 现货 {:.4%} + 合约 {:.4%} → 年化 {:.2%}（{} 天轮换）".format(
        SPOT_TAKER_FEE, PERP_TAKER_FEE, ANNUAL_COST, ROTATION_DAYS))
    print("恢复轮数过滤: {} | 目标币种池: {} 个币 | 数据区间: {} ~ {} ({} 天)".format(
        "开" if RECOVERY_FILTER else "关", len(turnover), dates[0], dates[-1], len(dates)))
    print()

    print_result("带滞回（本次规则）", run(by_symbol, dates, turnover, recovery, True))
    print_result("无滞回（每天重选，用于对比）", run(by_symbol, dates, turnover, recovery, False))


if __name__ == "__main__":
    main()
