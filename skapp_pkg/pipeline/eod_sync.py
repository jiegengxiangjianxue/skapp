"""收盘后快照同步（Actions 调用入口）。
流程：生成当日快照（行情/消息分析/推股/推股复盘）→ 写 data/snapshots/{date}.json →
清理 data/live/ 当日明细，仅保留快照。幂等：同日重跑覆盖同 sha 文件。
复盘：若存在前一日快照，用今日行情对昨日 picks 逐票结算（当日收盘 vs 昨日快照价）。
"""
from __future__ import annotations
import json, os, shutil, datetime as dt

HERE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(HERE, "data")

def _score(text: str) -> dict:
    pos = ["利好", "上涨", "增持", "回购", "中标", "增长", "超预期", "分红"]
    neg = ["利空", "下跌", "减持", "处罚", "亏损", "警示", "立案", "退市"]
    p = sum(t in text for t in pos); n = sum(t in text for t in neg)
    label = "利好" if p > n else ("利空" if n > p else "中性")
    return {"text": text, "sentiment": label, "hits": p - n}

def _load_watchlist() -> dict:
    import yaml
    cfg = yaml.safe_load(open(os.path.join(HERE, "py", "config", "symbols.yaml")))
    return {"watchlist": cfg.get("watchlist", []), "indices": cfg.get("indices", [])}

def _prev_snapshot(date: str) -> dict | None:
    snap_dir = os.path.join(DATA, "snapshots")
    if not os.path.isdir(snap_dir):
        return None
    dates = sorted(f[:-5] for f in os.listdir(snap_dir) if f.endswith(".json") and f[:-5] < date)
    if not dates:
        return None
    p = os.path.join(snap_dir, dates[-1] + ".json")
    try:
        return json.load(open(p, encoding="utf-8"))
    except Exception:
        return None

def _review_from_prev(prev: dict | None, quotes: list[dict]) -> list[dict]:
    """用今日收盘对昨日推股复盘"""
    if not prev:
        return []
    px = {q["symbol"]: q["price"] for q in quotes}
    out = []
    for p in (prev.get("picks", {}) or {}).get("items", []):
        sym = p.get("symbol")
        if not sym or sym not in px or not p.get("price"):
            continue
        ret = (px[sym] - p["price"]) / p["price"] * 100
        out.append({"symbol": sym, "ref_price": p["price"], "close": px[sym],
                    "ret_pct": round(ret, 2)})
    return out

def make_snapshot(date: str | None = None) -> dict:
    from skapp_pkg.datasrc.market_data import realtime_quotes, news_feed
    cfg = _load_watchlist()
    date = date or dt.date.today().isoformat()
    quotes = realtime_quotes(cfg["watchlist"]) or []

    news = news_feed(30)
    scored = [_score(n["text"]) for n in news]

    picks_path = os.path.join(DATA, "engine", "picks_latest.json")
    picks = json.load(open(picks_path)) if os.path.exists(picks_path) else {
        "source": "realtime_top", "items": sorted(
            quotes, key=lambda q: -q["pct_chg"])[:10]}

    prev = _prev_snapshot(date) if date == dt.date.today().isoformat() else None
    # 非今日（回填模式）时，prev 取该日期前一文件
    if prev is None:
        prev = _prev_snapshot(date)

    return {
        "date": date, "ts": int(dt.datetime.now().timestamp()),
        "quotes": quotes,
        "news_analysis": scored,
        "picks": picks,
        "review": {"items": _review_from_prev(prev, quotes)},
    }

def run(date: str | None = None):
    snap = make_snapshot(date)
    date = snap["date"]
    snap_dir = os.path.join(DATA, "snapshots")
    os.makedirs(snap_dir, exist_ok=True)
    path = os.path.join(snap_dir, f"{date}.json")
    with open(path + ".tmp", "w", encoding="utf-8") as f:
        json.dump(snap, f, ensure_ascii=False, default=str)
    os.replace(path + ".tmp", path)
    # 清理当日明细，仅保留快照
    live = os.path.join(DATA, "live")
    if os.path.isdir(live):
        shutil.rmtree(live, ignore_errors=True)
    print("snapshot written:", path, "| review items:", len(snap["review"]["items"]))
    return path

if __name__ == "__main__":
    import sys
    run(sys.argv[1] if len(sys.argv) > 1 else None)
