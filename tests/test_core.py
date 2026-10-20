"""skapp 单元测试（CI 与本地均可跑：python -m pytest tests/ -q）"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

def test_ranks_basic():
    from py.engine.analyze import _ranks
    r = _ranks([1.0, 2.0, 3.0])
    assert r == [0.0, 0.5, 1.0]

def test_ranks_ties():
    from py.engine.analyze import _ranks
    r = _ranks([5.0, 5.0, 1.0])
    assert r[0] == r[1] and r[2] < r[0]

def test_score():
    from py.pipeline.eod_sync import _score
    assert _score("公司回购股份，利好股东")["sentiment"] == "利好"
    assert _score("立案调查")["sentiment"] == "利空"
    assert _score("今天天气不错")["sentiment"] == "中性"

def test_review_settlement():
    from py.pipeline.eod_sync import _review_from_prev
    prev = {"picks": {"items": [{"symbol": "sh600519", "price": 100.0}]}}
    quotes = [{"symbol": "sh600519", "price": 102.0}]
    r = _review_from_prev(prev, quotes)
    assert r[0]["ret_pct"] == 2.0

def test_limit_up_thresholds():
    # Rust 不可用时跳过（扩展由 CI maturin 构建）
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "core", "target", "wheels"))
        import skapp_core
    except Exception:
        return
    assert skapp_core.limit_up_pct("sh600519", False) == 10.0
    assert skapp_core.limit_up_pct("sz300750", False) == 20.0
    assert skapp_core.limit_up_pct("sh600519", True) == 5.0
