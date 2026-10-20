# skapp

自选股观察与分析系统。对接 GitHub 历史股票数据库（db-daily-* 分片、db-fin-0、db-hk-0），
集成算法链（algo-core 因子库 + walk-forward）与引擎链（market_phase / 三选股池 / risk_filter / 加权融合），
提供实时行情与消息数据源接口、收盘后快照同步（同步后清理当日明细，仅保留快照），以及面向 AI 的 REST API。

## 结构

- `core/` — Rust 核心：行情数据结构、因子计算加速、截面 rank 加权
- `py/` — Python 层：数据源适配（腾讯/新浪/东财）、算法集成、引擎调度
- `api/` — FastAPI REST 接口（供 AI 接入分析）
- `app/` — Kotlin + Jetpack Compose (Material 3) Android 界面
- `.github/workflows/` — 收盘后快照同步 Action
