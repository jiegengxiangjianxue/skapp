"""算法+引擎集成入口：供 API 调用，把 algo-core 因子与 engine-core 融合逻辑接到实时数据上。
canonical 算法在 jiegengxiangjianxue/algo-core、engine-core；此处为集成层。
"""
from __future__ import annotations
import os, sys, json

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def ensure_canonical():
    """把 canonical 算法仓 clone/更新到 local/（Actions 与本地通用）"""
    base = os.path.join(HERE, "local")
    os.makedirs(base, exist_ok=True)
    import subprocess
    for repo in ("algo-core", "engine-core"):
        d = os.path.join(base, repo)
        if not os.path.isdir(d):
            subprocess.run(["git", "clone", "--depth", "1",
                            f"https://github.com/jiegengxiangjianxue/{repo}.git", d],
                           check=False, capture_output=True)
    for repo in ("algo-core", "engine-core"):
        p = os.path.join(base, repo)
        if os.path.isdir(p) and p not in sys.path:
            sys.path.insert(0, p)

def analyze(symbols: list[str]) -> dict:
    """对给定股票列表做引擎级分析：实时行情 + 因子排名（Rust 加速可用则用）"""
    from py.datasrc.market_data import realtime_quotes
    quotes = realtime_quotes(symbols)
    core = _try_rust()
    # 排序信号：综合涨幅与量能的截面 rank（升序名次归一）
    if core:
        ranks_chg = core.cross_rank([q["pct_chg"] for q in quotes])
    else:
        vals = sorted([q["pct_chg"] for q in quotes]); n = max(len(vals) - 1, 1)
        ranks_chg = [vals.index(q["pct_chg"]) / n if q["pct_chg"] in vals else 0.0 for q in quotes]
    items = [{**q, "rank_chg": round(r, 4)} for q, r in zip(quotes, ranks_chg)]
    return {"ts": max((q["ts"] for q in quotes), default=0), "engine": "orchestrator-lite",
            "items": sorted(items, key=lambda x: -x["rank_chg"])}

def _try_rust():
    try:
        sys.path.insert(0, os.path.join(HERE, "core", "target", "wheels"))
        import skapp_core as core  # 编译后的 pyo3 扩展
        return core
    except Exception:
        return None
