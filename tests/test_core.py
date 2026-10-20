"""skapp 单元测试（python -m pytest tests/ -q）"""
import os, sys
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

def test_ranks_basic():
    from skapp_pkg.engine.analyze import _ranks
    assert _ranks([1.0, 2.0, 3.0]) == [0.0, 0.5, 1.0]

def test_ranks_ties():
    from skapp_pkg.engine.analyze import _ranks
    r = _ranks([5.0, 5.0, 1.0])
    assert r[0] == r[1] and r[2] < r[0]

def test_score():
    from skapp_pkg.pipeline.eod_sync import _score
    assert _score("公司回购股份，利好股东")["sentiment"] == "利好"
    assert _score("立案调查")["sentiment"] == "利空"
    assert _score("今天天气不错")["sentiment"] == "中性"

def test_review_settlement():
    from skapp_pkg.pipeline.eod_sync import _review_from_prev
    prev = {"picks": {"items": [{"symbol": "sh600519", "price": 100.0}]}}
    quotes = [{"symbol": "sh600519", "price": 102.0}]
    assert _review_from_prev(prev, quotes)[0]["ret_pct"] == 2.0

def test_limit_up_thresholds():
    wheels = os.path.join(ROOT, "core", "target", "wheels")
    if os.path.isdir(wheels):
        sys.path.insert(0, wheels)
    try:
        import skapp_core
    except Exception:
        return
    assert skapp_core.limit_up_pct("sh600519", False) == 10.0
    assert skapp_core.limit_up_pct("sz300750", False) == 20.0
    assert skapp_core.limit_up_pct("sh600519", True) == 5.0
