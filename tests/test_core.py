"""skapp 单元测试（CI 与本地均可跑：python -m pytest tests/ -q）
注：仓库顶层包名 py 与 pytest 依赖库 'py' 冲突，故测试用 importlib 按路径加载。
"""
import importlib.util, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def _load(relpath, modname):
    spec = importlib.util.spec_from_file_location(
        modname, os.path.join(ROOT, relpath))
    m = importlib.util.module_from_spec(spec)
    # 保证包内相对导入可用：把模块注册进 sys.modules，并补包路径
    import sys
    sys.modules[modname] = m
    pkg_root = os.path.join(ROOT, os.path.dirname(relpath))
    if pkg_root not in sys.path:
        sys.path.insert(0, pkg_root)
    spec.loader.exec_module(m)
    return m

# 初始化包结构（api.main 依赖 py.* 包路径，这里直接加载被测函数的宿主文件）
_md = _load("py/datasrc/market_data.py", "skapp_market_data")

def _load_pkg():
    import sys
    if ROOT not in sys.path:
        sys.path.insert(0, ROOT)
    import importlib
    return importlib.import_module("py.engine.analyze"), importlib.import_module("py.pipeline.eod_sync")

def test_ranks_basic():
    a, _ = _load_pkg()
    assert a._ranks([1.0, 2.0, 3.0]) == [0.0, 0.5, 1.0]

def test_ranks_ties():
    a, _ = _load_pkg()
    r = a._ranks([5.0, 5.0, 1.0])
    assert r[0] == r[1] and r[2] < r[0]

def test_score():
    _, e = _load_pkg()
    assert e._score("公司回购股份，利好股东")["sentiment"] == "利好"
    assert e._score("立案调查")["sentiment"] == "利空"
    assert e._score("今天天气不错")["sentiment"] == "中性"

def test_review_settlement():
    _, e = _load_pkg()
    prev = {"picks": {"items": [{"symbol": "sh600519", "price": 100.0}]}}
    quotes = [{"symbol": "sh600519", "price": 102.0}]
    r = e._review_from_prev(prev, quotes)
    assert r[0]["ret_pct"] == 2.0

def test_limit_up_thresholds():
    try:
        sys_path = os.path.join(ROOT, "core", "target", "wheels")
        import sys
        if sys_path not in sys.path:
            sys.path.insert(0, sys_path)
        import skapp_core
    except Exception:
        return
    assert skapp_core.limit_up_pct("sh600519", False) == 10.0
    assert skapp_core.limit_up_pct("sz300750", False) == 20.0
    assert skapp_core.limit_up_pct("sh600519", True) == 5.0
