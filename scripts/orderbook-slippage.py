#!/usr/bin/env python3
"""
盘口滑点测算（模块 4 用来决定下单量的依据）。

用法:
    python3 scripts/orderbook-slippage.py BTCUSDT ETHUSDT ...

对每个币分别取 U 本位永续和现货的订单簿，模拟吃单：
    * 现货买入 / 现货卖出
    * 合约卖出开空 / 合约买入平空
并按几个典型下单金额输出滑点（成交均价相对盘口最优价的偏移）。

滑点是"相对最优价"的比例，方向都是不利方向，所以数值越大成本越高。
"""

import json
import os
import sys
import urllib.request

API = "https://api.bitget.com/api/v3/market/orderbook"
LEVEL_LIMIT = 500                      # 取多少档深度
# 测试的下单金额（USDT），可用环境变量 NOTIONALS 覆盖，例如 NOTIONALS=11000,112000
NOTIONALS = [int(x) for x in os.environ.get("NOTIONALS", "5000,20000,50000").split(",")]


def fetch_book(category, symbol):
    """取订单簿，返回 (asks, bids)，每项是 [价格, 数量]。"""
    url = "{}?category={}&symbol={}&limit={}".format(API, category, symbol, LEVEL_LIMIT)
    with urllib.request.urlopen(url, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("code") != "00000" or not payload.get("data"):
        raise RuntimeError("{} 订单簿获取失败: {}".format(symbol, payload.get("msg")))
    data = payload["data"]
    return data.get("a") or [], data.get("b") or []


def slippage(levels, notional):
    """按给定金额吃单，返回（滑点比例, 成交均价）；深度不足返回 (None, None)。"""
    if not levels:
        return None, None
    quote = 0.0
    base = 0.0
    for level in levels:
        price = float(level[0])
        size = float(level[1])
        level_quote = price * size
        take_quote = min(level_quote, notional - quote)
        if take_quote <= 0:
            break
        base += take_quote / price
        quote += take_quote
        if quote >= notional - 1e-9:
            break
    if quote < notional * 0.999 or base <= 0:
        return None, None
    avg_price = quote / base
    best_price = float(levels[0][0])
    return abs(avg_price / best_price - 1), avg_price


def main():
    symbols = sys.argv[1:]
    if not symbols:
        print(__doc__)
        sys.exit(1)

    header = "{:<14}".format("币种")
    for notional in NOTIONALS:
        header += "{:>26}".format("{} USDT".format(notional // 1000) + "k 现货买/合约空")
    print(header)

    for symbol in symbols:
        try:
            spot_asks, spot_bids = fetch_book("SPOT", symbol)
            perp_asks, perp_bids = fetch_book("USDT-FUTURES", symbol)
        except Exception as exc:                       # noqa: BLE001 - 单个币失败不影响其他
            print("{:<14} 取盘口失败: {}".format(symbol, exc))
            continue

        line = "{:<14}".format(symbol)
        for notional in NOTIONALS:
            # 建仓：现货买入（吃卖档）+ 合约卖出开空（吃买档）
            spot_slip, _ = slippage(spot_asks, notional)
            perp_slip, _ = slippage(perp_bids, notional)
            if spot_slip is None or perp_slip is None:
                line += "{:>26}".format("深度不足")
            else:
                line += "{:>26}".format("{:.4%} + {:.4%}".format(spot_slip, perp_slip))
        print(line)


if __name__ == "__main__":
    main()
