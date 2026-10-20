"""收盘后快照同步（Actions 调用入口）。
流程：生成当日快照（行情/消息分析/推股/推股复盘）→ 写 data/snapshots/{date}.json →
清理 data/live/ 当日明细，仅保留快照。幂等：同日重跑覆盖同 sha 文件。
"""
from __future__ import annotations
import json, os, shutil, datetime as dt

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(HERE, "data")

def make_snapshot(date: str | None = None) -> dict:
    from py.datasrc.market_data import realtime_quotes, news_feed
    cfg = _load_watchlist()
    date = date or dt.date.today().isoformat()
    syms = cfg["watchlist"]

    quotes = realtime_quotes(syms) or []

    # 消息分析：轻量关键词打分（利好/利空/中性），只报告事实不预测
    news = news_feed(30)
    scored = [_score(n["text"]) for n in news]

    # 推股：读取最近一次引擎产物（若有），否则按实时涨幅排序观察
    picks_path = os.path.join(DATA, "engine", "picks_latest.json")
    picks = json.load(open(picks_path)) if os.path.exists(picks_path) else {
        "source": "realtime_top", "items": sorted(
            quotes, key=lambda q: -q["pct_chg"])[:10]}

    review_path = os.path.join(DATA, "engine", "review_latest.json")
    review = json.load(open(review_path)) if os.path.exists(review_path) else {"items": []}

    return {
        "date": date, "ts": int(dt.datetime.now().timestamp()),
        "quotes": quotes,
        "news_analysis": scored,
        "picks": picks, "review": review,
    }

def _score(text: str) -> dict:
    pos = ["利好", "上涨", "增持", "回购", "中标", "增长", "超预期", "分红"]
    neg = ["利空", "下跌", "减持", "处罚", "亏损", "警示", "立案", "退市"]
    p = sum(t in text for t in pos); n = sum(t in text for t in neg)
    label = "利好" if p > n else ("利空" if n > p else "中性")
    return {"ts": "", "text": text, "sentiment": label, "hits": p - n}

def _load_watchlist() -> dict:
    import yaml  # 延迟导入
    cfg = yaml.safe_load(open(os.path.join(HERE, "py", "config", "symbols.yaml")))
    return {"watchlist": cfg.get("watchlist", []), "indices": cfg.get("indices", [])}

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
    print("snapshot written:", path)
    return path

if __name__ == "__main__":
    import sys
    run(sys.argv[1] if len(sys.argv) > 1 else None)
