# 差点指数重算 · HANDICAP_INDEX

在签核闭环之上迭代「球员差点指数」：指数不再是球员表上一个可随手改的整数，而是
**由最近二十轮已签核成绩 + 版本化规则**重算出来、且**每次计算都留痕**的派生结果。

## 数据口径（七张表）

| 表 | 角色 |
| --- | --- |
| `course_rating` | 球场难度参数（场地×梯台：course_rating / slope_rating / par_total） |
| `handicap_rule` | 版本化计算规则（生效日唯一；已生效锁定，只能新建更晚生效的规则） |
| `handicap_round` | 已签核卡的派生层：一行一卡，存总杆/封顶后总杆/差值/缺参数标记 |
| `handicap_index_log` | 每次计算一个节点（LIVE 当前链 / SUPERSEDED 被重算作废旧链） |
| `handicap_index_round` | 节点窗口内每轮的采用/排除留痕（差值快照 + 排名 + 排除原因） |
| `recalc_batch` / `recalc_batch_failure` | 重算批次与逐球员失败明细 |

记分卡新增 `play_date`（完赛日）、`course_rating_id`（本轮梯台）；
赛事表新增默认梯台。`player` 上的 `handicap_index/index_status/index_as_of`
**只是当前链最新节点的缓存，只有 HandicapService 能写**，球员新增/编辑接口忽略页面传入值。

## 计算口径

1. **只取已签核轮**：`handicap_round` 由签核动作同步 upsert，数据来自固化快照；
   草稿/待确认/争议中卡一律缺席。
2. **规则生效边界**：节点截止日 `as_of_date` 只选 `effective_date <= 截止日` 的最新启用规则。
   历史链重放时每个节点都重新选当时规则——新规则永远算不进生效日之前的节点。
3. **差值**：`diff = 113 / slope ×（逐洞封顶后总杆 − course_rating）`，一位小数。
   - 梯台缺失：`rating_missing=1`、差值留空，窗口内占位并标「难度参数缺失」。
   - 封顶：重放时按**节点当时规则**的 `hole_score_cap` 重新从快照逐洞计算（派生层缓存的是最近口径）。
4. **样本**：窗口取最近 `window_rounds` 轮（缺参数轮也占位）；缺参数轮与异常轮不计有效样本；
   有效样本 < `min_rounds`（3）记 `INSUFFICIENT`「样本不足」。
5. **选轮**：按 `count_table_json`（WHS：3 取 1…20 取 10）选最低差值，平均后 −2，四舍五入一位小数。
6. **两道防失真闸门**：
   - 单轮差值高出「窗口内最佳差值 + `max_diff_below_index`」判异常排除（防单洞录错抬高总杆）；
   - 单次指数相对上一节点下调超过 `max_decrease_per_round` 则截断（上调不限），再做 `[index_min, index_max]` 钳制。

## 可追溯性

- 实时签核：`ScorecardService.confirm` 同事务调用 `onSignedOff` → 派生本轮 → 追加一个 LIVE 节点。
- 重算（定向/全量）：把截止日之后的 LIVE 节点标 `SUPERSEDED`（行不删，记 `superseded_by=批次号`），
  再按每个完赛日逐个重算追加新链。历史指数因此永远可按当时规则回放，新口径改不了旧数字。
- 页面「指数详情」可看含旧链的完整历史：节点、当时规则、选轮均值、最终指数、排除原因。

## 缺参数与定向重算

- 旧卡没梯台：球员页明确列出缺参数轮；管理员在梯台页建参数，
  再用 `POST /api/scorecards/{id}/round-meta?courseRatingId=` 补绑，
  或直接发起 `POST /api/handicap/recalc/target?playerId=` / `?ratingId=` 定向重算。

## 全量重算（十万条逐洞成绩）

`POST /api/handicap/recalc/full?from=&to=` 后台异步、按赛事日期分批：

1. 一条聚合 SQL 从已签核主单+快照重建区间内 `handicap_round`（不逐球员扫逐洞表）；
2. 球员集合来自区间 `distinct player_id`；
3. 每球员独立事务重算链，单球员失败只写 `recalc_batch_failure`（阶段 CALC_CHAIN），不回滚整批；
4. 批次终态 已完成 / 部分失败 / 失败，页面可展开失败明细后按球员定向重试。

## 接口

- `GET /api/handicap/players/{id}/profile` 当前指数、最近变化、当前节点采用/排除轮次、规则、缺参数轮
- `GET /api/handicap/players/{id}/history` 含旧链全量历史
- `POST /api/handicap/recalc/full` / `POST /api/handicap/recalc/target`
- `GET /api/handicap/recalc-batches` / `GET /api/handicap/recalc-batches/{id}`
- `/api/handicap/admin/ratings`（梯台 CRUD）、`/api/handicap/admin/rules`（规则 CRUD + 停用）
- `POST /api/scorecards/{id}/round-meta` 给旧卡补梯台/完赛日

启动时若一张日志都没有，`HandicapBootstrapRunner` 按已签核卡做一次性初始回填（失败不阻断启动）。
自测用例见 `docs-handicap-api-checks.http`。
