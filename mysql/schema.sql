SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 高尔夫球场 · 球洞 / 开球时间 / 球员 / 赛事 / 成绩
-- 库名 golf_course；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 注意：mysql 容器是 UTC，种子数据里的时间一律写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。

CREATE DATABASE IF NOT EXISTS golf_course
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE golf_course;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS recalc_batch_failure;
DROP TABLE IF EXISTS recalc_batch;
DROP TABLE IF EXISTS handicap_index_round;
DROP TABLE IF EXISTS handicap_index_log;
DROP TABLE IF EXISTS handicap_round;
DROP TABLE IF EXISTS handicap_rule;
DROP TABLE IF EXISTS course_rating;
DROP TABLE IF EXISTS scorecard_event;
DROP TABLE IF EXISTS scorecard_hole;
DROP TABLE IF EXISTS scorecard;
DROP TABLE IF EXISTS tournament_entry;
DROP TABLE IF EXISTS score;
DROP TABLE IF EXISTS leaderboard;
DROP TABLE IF EXISTS tee_time;
DROP TABLE IF EXISTS tournament;
DROP TABLE IF EXISTS player;
DROP TABLE IF EXISTS hole;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 球洞 ----------------
-- 状态：开放 / 维护 / 封闭；par 取 3 / 4 / 5
CREATE TABLE hole (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    hole_no INT NOT NULL UNIQUE,
    par INT NOT NULL,
    yardage INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 球员 ----------------
-- handicap：旧的整数差点展示字段（建档人工值）；差点指数改由 handicap_index_log 当前链派生，
-- handicap_index / index_status / index_as_of / index_updated_at 为其反范式缓存，
-- 只能由重算服务写入，页面直接改这几个数字不留痕，一律拒绝（见 HandicapService）。
CREATE TABLE player (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_no VARCHAR(16) NOT NULL UNIQUE,
    name VARCHAR(48) NOT NULL,
    handicap INT NOT NULL,
    handicap_index DECIMAL(4,1) NULL,
    index_status VARCHAR(16) NULL,
    index_as_of DATE NULL,
    index_updated_at DATETIME NULL,
    phone VARCHAR(24),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 开球时间 ----------------
-- 同一球员同一天同一时段唯一（防止重复预订）；状态：可订 / 已订
CREATE TABLE tee_time (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    play_date DATE NOT NULL,
    player_id BIGINT NOT NULL,
    slot VARCHAR(8) NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(64),
    created_at DATETIME,
    UNIQUE KEY uk_tee_player_slot (player_id, play_date, slot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事 ----------------
-- 状态：报名中 → 进行中 → 已结束
CREATE TABLE tournament (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_no VARCHAR(24) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    start_date DATE,
    end_date DATE,
    status VARCHAR(16) NOT NULL,
    location VARCHAR(48),
    note VARCHAR(64),
    -- 赛事默认梯台：新建记分卡草稿时自动带出（记分员可在卡上改）
    course_rating_id BIGINT NULL,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事排行榜（乐观锁演示实体） ----------------
-- version 为 @Version 乐观锁版本号
CREATE TABLE leaderboard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    leader_name VARCHAR(48),
    total_score INT,
    version BIGINT,
    updated_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 成绩（按洞录入） ----------------
CREATE TABLE score (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    tournament_id BIGINT NOT NULL,
    hole_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    strokes INT NOT NULL,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事参赛名单（签核闭环的参赛范围） ----------------
-- 一场赛事 × 一名球员 × 一个轮次唯一；round_count 为该赛事应打轮次数。
-- 赛事收口时，每个参赛名额都必须存在对应轮次的「已签核」记分卡，否则阻止结束。
CREATE TABLE tournament_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    round_count INT NOT NULL DEFAULT 1,
    created_at DATETIME,
    UNIQUE KEY uk_entry_tour_player (tournament_id, player_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 赛事记分卡（签核闭环主单，@Version 乐观锁） ----------------
-- 一张卡 = 赛事 × 轮次 × 球员，唯一。
-- 状态机：草稿 → 待球员确认 → 争议中 → 待球员确认 → … → 已签核（终态）
--   草稿：记分员可反复维护逐洞草稿；提交后 snapshot 固化，普通编辑不得改写。
--   待球员确认：等待球员整卡确认或对具体球洞提争议；确认不可撤回。
--   争议中：仅裁判可处理（维持 / 修正），裁决后回到待球员确认。
--   已签核：正式收口，其快照才计入正式排行榜；历史版本永不重复计入。
-- version 为 JPA @Version 乐观锁号：多人并发提交/确认/裁决时只有基于最新版本的一次成功。
CREATE TABLE scorecard (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tournament_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    expected_holes INT NOT NULL,
    total_strokes INT,
    -- 本轮比赛日期（默认取赛事 start_date，可按实际完赛日修正）；差点计算以它为轮次完赛日
    play_date DATE NULL,
    -- 本轮使用的梯台（球场难度参数）；旧数据允许为空，计算差点时该轮记「难度参数缺失」
    course_rating_id BIGINT NULL,
    -- 本次已固化快照：提交 / 每次裁决时重写为当前逐洞成绩的完整 JSON，历史快照存 scorecard_event
    snapshot_json MEDIUMTEXT,
    submitted_at DATETIME NULL,
    confirmed_at DATETIME NULL,
    last_submitter VARCHAR(48),
    created_at DATETIME,
    updated_at DATETIME,
    UNIQUE KEY uk_scorecard_tour_player_round (tournament_id, player_id, round_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 记分卡逐洞明细（草稿工作区 + 争议/裁决工作数据） ----------------
-- 一张卡内洞号唯一（DB 层兜底，防止重复洞号）；strokes 为当前工作杆数。
-- dispute_reason：球员对该洞提出的争议理由（非空即表示该洞处于争议）。
-- ruled_strokes / ruling_note：裁判对该洞的修正杆数与裁决说明。
-- snapshot 固化后这些行保留为工作数据，但正式成绩只读 scorecard.snapshot_json。
CREATE TABLE scorecard_hole (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scorecard_id BIGINT NOT NULL,
    hole_no INT NOT NULL,
    par INT NOT NULL,
    strokes INT,
    dispute_reason VARCHAR(255),
    ruled_strokes INT,
    ruling_note VARCHAR(255),
    updated_at DATETIME,
    UNIQUE KEY uk_card_hole (scorecard_id, hole_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 记分卡流转事件（可追溯审计，永不修改/删除） ----------------
-- 每次提交、确认、争议、裁决都追加一行：
--   before_json 操作前快照（草稿首次提交可为空），after_json 操作后快照。
-- action：提交 / 确认 / 争议 / 维持 / 修正
CREATE TABLE scorecard_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scorecard_id BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL,
    actor VARCHAR(48) NOT NULL,
    detail VARCHAR(512),
    before_status VARCHAR(16),
    after_status VARCHAR(16),
    before_version BIGINT,
    after_version BIGINT,
    before_json MEDIUMTEXT,
    after_json MEDIUMTEXT,
    created_at DATETIME,
    KEY idx_event_card (scorecard_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 球场难度参数（梯台） ----------------
-- 差点差值 = 113 / slope_rating ×（总杆 - course_rating），旧数据没有梯台时
-- 记分卡的 course_rating_id 允许为空，差点计算将把该轮标记为「难度参数缺失」并排除。
-- 同一场地同一梯台唯一。
CREATE TABLE course_rating (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    course_name VARCHAR(48) NOT NULL,
    tee_name VARCHAR(24) NOT NULL,
    course_rating DECIMAL(4,1) NOT NULL,
    slope_rating INT NOT NULL,
    par_total INT,
    note VARCHAR(128),
    created_at DATETIME,
    UNIQUE KEY uk_course_tee (course_name, tee_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 差点计算规则（版本化，按生效日切换） ----------------
-- 每条规则自 effective_date 起生效；计算任意「截止日」时只取生效日 <= 截止日的最新一条。
-- 新规则只影响生效日之后新产生/重算的结果；历史差点日志链永不改写（重算只作废旧链、追加新链）。
-- window_rounds：取最近 N 轮；min_rounds：少于该样本数显示「样本不足」；
-- count_table_json：样本数 -> 采用最低差值个数，如 {"3":1,"4":1,"5":2,...,"20":8}（WHS 口径）；
-- max_diff_below_index：单轮差值高出「窗口内最佳差值」超过该值即判异常并排除（防止录入错误）；
-- max_decrease_per_round：单次重算指数下调幅度上限（上调不限制）；
-- hole_score_cap：逐洞杆数上限（Net Double Bogey 口径的简化），为空表示不封顶；
-- index_min / index_max：指数钳制区间。
-- 规则不物理删除（历史计算要能回放），以 enabled=0 停用；生效日不得与既有规则重复。
CREATE TABLE handicap_rule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(24) NOT NULL UNIQUE,
    name VARCHAR(64) NOT NULL,
    effective_date DATE NOT NULL,
    window_rounds INT NOT NULL,
    min_rounds INT NOT NULL,
    count_table_json VARCHAR(512) NOT NULL,
    max_diff_below_index DECIMAL(4,1) NOT NULL,
    max_decrease_per_round DECIMAL(4,1) NOT NULL,
    hole_score_cap INT,
    index_min DECIMAL(4,1) NOT NULL,
    index_max DECIMAL(4,1) NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    note VARCHAR(255),
    created_at DATETIME,
    UNIQUE KEY uk_rule_effective (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 已签核轮次派生层（差点计算的唯一取数口径） ----------------
-- 一张已签核记分卡对应一行；由签核动作同步派生、参数补齐/定向重算时批量重建。
-- 全量重算按 play_date 键集分页扫这张小表 + 一次性 SQL 聚合逐洞杆数，
-- 不会对十万条逐洞成绩做「逐球员反复全表扫描」。
-- total_strokes / total_par 来自签核快照；adjusted_strokes 为按规则逐洞封顶后的总杆（v1 不封顶时等于总杆）。
-- course_rating 缺失时 diff 为空、rating_missing=1。
CREATE TABLE handicap_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    scorecard_id BIGINT NOT NULL UNIQUE,
    tournament_id BIGINT NOT NULL,
    player_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    play_date DATE NOT NULL,
    total_strokes INT NOT NULL,
    adjusted_strokes INT NOT NULL,
    total_par INT NOT NULL,
    hole_cap INT NULL,
    course_rating_id BIGINT NULL,
    diff_value DECIMAL(5,1) NULL,
    rating_missing TINYINT NOT NULL DEFAULT 0,
    updated_at DATETIME,
    KEY idx_hr_player_date (player_id, play_date, id),
    KEY idx_hr_date_id (play_date, id),
    KEY idx_hr_missing (rating_missing, play_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 差点指数计算日志（每次计算一行，追加不删） ----------------
-- chain：LIVE 当前链 / SUPERSEDED 被后续重算作废；同一次重算作废的旧行共享 superseded_by。
-- status：CALCULATED 正常出指数 / INSUFFICIENT 样本不足 / NO_RULE 截止日没有生效规则。
-- rule_id：本次实际采用的规则版本；raw_index 选轮平均（-2 已并入差值口径，见服务注释），
-- final_index 经「单次下调上限 + 钳制」后的结果，保留一位小数。
-- batch_id：由哪次批量/定向重算产生（实时签核触发为空）。
CREATE TABLE handicap_index_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_id BIGINT NOT NULL,
    as_of_date DATE NOT NULL,
    rule_id BIGINT NULL,
    status VARCHAR(16) NOT NULL,
    sample_count INT NOT NULL,
    used_count INT NOT NULL,
    raw_index DECIMAL(5,1) NULL,
    final_index DECIMAL(5,1) NULL,
    previous_index DECIMAL(5,1) NULL,
    capped_note VARCHAR(255),
    chain VARCHAR(16) NOT NULL,
    batch_id BIGINT NULL,
    trigger_type VARCHAR(24) NOT NULL,
    superseded_by BIGINT NULL,
    created_at DATETIME,
    KEY idx_hil_player_chain (player_id, chain, as_of_date, id),
    KEY idx_hil_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 计算轮次留痕：本次窗口内每轮是否采用及排除原因 ----------------
-- log_id 指向 handicap_index_log；窗口内（最近 N 轮 + 缺参数轮）每轮一行，
-- included=1 为采用，included=0 时 exclusion_reason 写明排除原因：
--   样本不足未出指数 / 排名未入选（第N低规则）/ 单轮差值异常（低于上一指数超阈值）/ 难度参数缺失
-- diff_value 为当时采用的差值（重算口径可能变化，故随日志固化，不随后续改动漂移）。
CREATE TABLE handicap_index_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_id BIGINT NOT NULL,
    handicap_round_id BIGINT NOT NULL,
    tournament_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    play_date DATE NOT NULL,
    total_strokes INT NOT NULL,
    diff_value DECIMAL(5,1) NULL,
    included TINYINT NOT NULL,
    rank_no INT,
    exclusion_reason VARCHAR(128),
    KEY idx_hir_log (log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 差点重算批次（全量/定向，按赛事日期分批执行） ----------------
-- 类型：FULL 全量 / TARGET 定向（补齐难度参数后指定球员或梯台）。
-- 状态：排队中 → 执行中 → 已完成 / 部分失败 / 失败。
-- date_from/date_to：本批处理的赛事（轮次）日期区间，分批执行、互不覆盖。
CREATE TABLE recalc_batch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no VARCHAR(24) NOT NULL UNIQUE,
    batch_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    date_from DATE,
    date_to DATE,
    scope_player_id BIGINT NULL,
    scope_rating_id BIGINT NULL,
    total_players INT NOT NULL DEFAULT 0,
    success_players INT NOT NULL DEFAULT 0,
    failed_players INT NOT NULL DEFAULT 0,
    total_logs INT NOT NULL DEFAULT 0,
    fail_count INT NOT NULL DEFAULT 0,
    fail_detail VARCHAR(1024),
    started_at DATETIME,
    finished_at DATETIME,
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 重算失败明细（逐球员 × 截止日） ----------------
CREATE TABLE recalc_batch_failure (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    player_id BIGINT NULL,
    as_of_date DATE NULL,
    stage VARCHAR(32),
    error_message VARCHAR(512),
    created_at DATETIME,
    KEY idx_rbf_batch (batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- =====================================================================

INSERT INTO hole (id, hole_no, par, yardage, status, created_at) VALUES
(1, 1, 4, 412, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(2, 2, 5, 528, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(3, 3, 3, 168, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(4, 4, 4, 395, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(5, 5, 4, 430, '维护', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(6, 6, 3, 190, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(7, 7, 5, 545, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(8, 8, 4, 408, '开放', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(9, 9, 3, 205, '封闭', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY);

INSERT INTO player (id, player_no, name, handicap, phone, note, created_at) VALUES
(1,  'P-001', '林浩',     12, '13801012001', '单差点进阶',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(2,  'P-002', '陈雨桐',   18, '13801012002', '周末常客',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(3,  'P-003', '王启明',    8, '13801012003', '低差点',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(4,  'P-004', '赵子涵',   24, '13801012004', '新手',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(5,  'P-005', '刘振华',    0, '13801012005', '职业教练',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(6,  'P-006', '孙佳怡',   15, '13801012006', '女子组',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(7,  'P-007', '周文博',   30, '13801012007', '长者组',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(8,  'P-008', '吴晓彤',   20, '13801012008', '业余赛事常客',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(9,  'P-009', '郑凯',     10, '13801012009', '稳定发挥',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY),
(10, 'P-010', '黄诗韵',   28, '13801012010', '新人',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 DAY);

INSERT INTO tee_time (id, play_date, player_id, slot, status, note, created_at) VALUES
(1,  '2026-10-01', 1,  '07:00', '已订', '晨场第一开球',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(2,  '2026-10-01', 3,  '07:10', '已订', NULL,             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(3,  '2026-10-01', 5,  '09:00', '已订', '教练陪打',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(4,  '2026-10-01', 2,  '09:10', '可订', '待确认',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 DAY),
(5,  '2026-10-02', 4,  '08:00', '已订', '新手首场',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
(6,  '2026-10-02', 6,  '08:10', '已订', NULL,             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
(7,  '2026-10-02', 7,  '13:30', '已订', '长者组',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 DAY),
(8,  '2026-10-03', 8,  '10:00', '已订', '赛事热身',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY),
(9,  '2026-10-03', 9,  '10:10', '可订', '待拼场',         UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY),
(10, '2026-10-03', 10, '15:00', '已订', '新人练习',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 DAY);

INSERT INTO tournament (id, tournament_no, name, start_date, end_date, status, location, note, created_at) VALUES
(1, 'T-2026-001', '金秋会员邀请赛', '2026-10-10', '2026-10-12', '报名中', 'A场主球场', '9 洞比杆赛',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
(2, 'T-2026-002', '青少年高尔夫训练营', '2026-11-01', '2026-11-03', '报名中', '练习场', '低差点组',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 DAY);

INSERT INTO leaderboard (id, tournament_id, round_no, leader_name, total_score, version, updated_at) VALUES
(1, 1, 1, '王启明', 33, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2, 1, 2, '林浩',   35, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(3, 2, 1, '孙佳怡', 40, 0, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

INSERT INTO score (id, player_id, tournament_id, hole_id, round_no, strokes, created_at) VALUES
(1,  1, 1, 1, 1, 4,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2,  1, 1, 2, 1, 6,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3,  1, 1, 3, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(4,  3, 1, 1, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(5,  3, 1, 2, 1, 5,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(6,  3, 1, 3, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(7,  5, 1, 1, 1, 4,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(8,  5, 1, 2, 1, 5,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(9,  6, 2, 4, 1, 5,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(10, 6, 2, 5, 1, 4,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(11, 6, 2, 6, 1, 3,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(12, 8, 2, 7, 1, 6,  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY);

-- =====================================================================
-- 种子数据：赛事参赛名单（赛事 1 为 7 个开放洞的一轮比杆赛）
-- =====================================================================
INSERT INTO tournament_entry (id, tournament_id, player_id, round_count, created_at) VALUES
(1, 1, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(2, 1, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(3, 1, 3, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(4, 1, 5, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(5, 2, 6, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY),
(6, 2, 8, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY);

-- =====================================================================
-- 种子数据：记分卡签核闭环演示
--   卡1 刘振华：草稿且 7 洞齐全（可直接体验提交）
--   卡2 林浩：草稿缺洞（提交时应被拦截并列出缺失/重复原因）
--   卡3 王启明：已提交待球员确认（快照已固化）
--   卡4 陈雨桐：争议中（第 2 洞，等待裁判裁决；赛事收口时应阻止结束）
-- =====================================================================
INSERT INTO scorecard
(id, tournament_id, player_id, round_no, status, version, expected_holes, total_strokes, snapshot_json,
 submitted_at, confirmed_at, last_submitter, created_at, updated_at) VALUES
(1, 1, 5, 1, '草稿', 0, 7, NULL, NULL,
 NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2, 1, 1, 1, '草稿', 0, 7, NULL, NULL,
 NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3, 1, 3, 1, '待球员确认', 1, 7, 28,
 '{"roundNo":1,"totalStrokes":28,"holes":[{"holeNo":1,"par":4,"strokes":3},{"holeNo":2,"par":5,"strokes":5},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":4},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":6},{"holeNo":8,"par":4,"strokes":4}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(4, 1, 2, 1, '争议中', 2, 7, 33,
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY, NULL, '记分员小周',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- 卡1 刘振华草稿：7 洞齐全
INSERT INTO scorecard_hole (id, scorecard_id, hole_no, par, strokes, dispute_reason, ruled_strokes, ruling_note, updated_at) VALUES
(1,  1, 1, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(2,  1, 2, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(3,  1, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(4,  1, 4, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(5,  1, 6, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(6,  1, 7, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(7,  1, 8, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
-- 卡2 林浩草稿：只录了 5 洞（缺第 7、8 洞），提交时应被拦截
(8,  2, 1, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(9,  2, 2, 5, 6, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(10, 2, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(11, 2, 4, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
(12, 2, 6, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 DAY),
-- 卡3 王启明：待确认快照对应逐洞
(13, 3, 1, 4, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(14, 3, 2, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(15, 3, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(16, 3, 4, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(17, 3, 6, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(18, 3, 7, 5, 6, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
(19, 3, 8, 4, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
-- 卡4 陈雨桐：争议中，第 2 洞球员主张记错（6 → 应为 5）
(20, 4, 1, 4, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(21, 4, 2, 5, 6, '同组均记为5杆，记分卡6杆系笔误', NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(22, 4, 3, 3, 3, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(23, 4, 4, 4, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(24, 4, 6, 3, 4, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(25, 4, 7, 5, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
(26, 4, 8, 4, 5, NULL, NULL, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- 卡3：提交事件（before 为空草稿，after 为固化快照）
INSERT INTO scorecard_event
(id, scorecard_id, action, actor, detail, before_status, after_status, before_version, after_version,
 before_json, after_json, created_at) VALUES
(1, 3, '提交', '记分员小周', '7 洞齐全，提交后进入待球员确认', '草稿', '待球员确认', 0, 1,
 NULL,
 '{"roundNo":1,"totalStrokes":28,"holes":[{"holeNo":1,"par":4,"strokes":3},{"holeNo":2,"par":5,"strokes":5},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":4},{"holeNo":6,"par":3,"strokes":3},{"holeNo":7,"par":5,"strokes":6},{"holeNo":8,"par":4,"strokes":4}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 DAY),
-- 卡4：提交 → 球员就第 2 洞提争议
(2, 4, '提交', '记分员小周', '7 洞齐全，提交后进入待球员确认', '草稿', '待球员确认', 0, 1,
 NULL,
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR),
(3, 4, '争议', '陈雨桐', '第 2 洞提出争议：同组均记为5杆，记分卡6杆系笔误', '待球员确认', '争议中', 1, 2,
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 '{"roundNo":1,"totalStrokes":33,"holes":[{"holeNo":1,"par":4,"strokes":5},{"holeNo":2,"par":5,"strokes":6,"disputeReason":"同组均记为5杆，记分卡6杆系笔误"},{"holeNo":3,"par":3,"strokes":3},{"holeNo":4,"par":4,"strokes":5},{"holeNo":6,"par":3,"strokes":4},{"holeNo":7,"par":5,"strokes":5},{"holeNo":8,"par":4,"strokes":5}]}',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR);

-- =====================================================================
-- 种子数据：差点指数重算功能
--   梯台：A 场白梯（72.8/128，18 洞）、本练习场 9 洞白梯（28.4/115，7 洞 par28）
--   规则：v1 自 2026-01-01 生效；v2 自 2026-10-01 生效（异常阈值与单次下调上限收紧）
--   三场历史已结束赛事共 14 张已签核卡：
--     林浩5轮（含 10/04 跨 v2 规则轮）、王启明4轮（含一轮异常高杆被排除）、
--     陈雨桐2轮（样本不足）、刘振华2轮（样本不足）；
--     卡13 黄诗韵、卡14 陈雨桐 9 月球场所打卡故意不绑梯台 —— 难度参数缺失演示。
-- =====================================================================
INSERT INTO course_rating (id, course_name, tee_name, course_rating, slope_rating, par_total, note, created_at) VALUES
(1, 'A场主球场', '白梯', 72.8, 128, 72, '男子常规梯台', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(2, 'A场主球场', '蓝梯', 75.2, 135, 72, '男子长打梯台', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(3, '练习场9洞', '白梯', 28.4, 115, 28, '当前 7 个开放洞合计 par', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(4, 'A场主球场', '7洞临时场', 28.4, 128, 28, '历史月例赛曾使用的 7 洞半场', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY);

INSERT INTO handicap_rule
(id, code, name, effective_date, window_rounds, min_rounds, count_table_json,
 max_diff_below_index, max_decrease_per_round, hole_score_cap, index_min, index_max, enabled, note, created_at) VALUES
(1, 'WHS-V1', '俱乐部差点规则 v1（WHS 口径）', DATE '2026-01-01', 20, 3,
 '{"3":1,"4":1,"5":2,"6":2,"7":3,"8":4,"9":4,"10":5,"11":5,"12":6,"13":6,"14":7,"15":7,"16":8,"17":8,"18":9,"19":9,"20":10}',
 10.0, 5.0, NULL, -10.0, 54.0, 1, '初始规则：单轮差值高出本轮窗口最低差值 10.0 即判异常；单次最多下调 5.0；不做逐洞封顶',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 120 DAY),
(2, 'WHS-V2', '俱乐部差点规则 v2（收紧异常与下调）', DATE '2026-10-01', 20, 3,
 '{"3":1,"4":1,"5":2,"6":2,"7":3,"8":3,"9":4,"10":4,"11":5,"12":5,"13":6,"14":6,"15":7,"16":7,"17":8,"18":8,"19":9,"20":10}',
 8.0, 2.0, 7, -10.0, 54.0, 1, '10 月起：异常阈值收紧到 8.0、单次最多下调 2.0、逐洞封顶 7；仅影响 10/01 之后的计算',
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY);

INSERT INTO tournament (id, tournament_no, name, start_date, end_date, status, location, note, course_rating_id, created_at) VALUES
(3, 'T-2026-081', '夏季会员杯（第一场）', DATE '2026-08-15', DATE '2026-08-16', '已结束', 'A场主球场', '差点演示历史赛事', 1,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(4, 'T-2026-091', '九月会员月例赛',     DATE '2026-09-12', DATE '2026-09-13', '已结束', 'A场主球场', '差点演示历史赛事', 1,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(5, 'T-2026-103', '国庆让分赛',         DATE '2026-10-04', DATE '2026-10-04', '已结束', 'A场主球场', 'v2 规则生效后首场', 1,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
-- 旧场地友谊赛：已结束但未维护梯台难度参数（卡13/14 缺参数演示）
(6, 'T-2026-099', '老球场友谊赛',         DATE '2026-09-20', DATE '2026-09-21', '已结束', '老球场9洞', '旧场地，梯台参数待补', NULL,
 UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 DAY);

INSERT INTO tournament_entry (id, tournament_id, player_id, round_count, created_at) VALUES
(7,  3, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(8,  3, 3, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(9,  3, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(10, 3, 5, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 60 DAY),
(11, 4, 1, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(12, 4, 3, 2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(13, 4, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(14, 4, 5, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 35 DAY),
(15, 5, 1, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 10 DAY),
(16, 6, 10, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 DAY),
(17, 6, 2, 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 25 DAY);

-- 已签核历史卡（snapshot_json / scorecard_hole 由下面的 CTE 按 7 个开放洞生成）。
-- adj 为相对 7 洞 par 合计(28) 的加杆；卡10 adj=18（46 杆，差值 15.6）是异常高杆演示
-- （其杆数按 +3/+2/+3/+3/+2/+2/+3 摊到 7 洞，明显高出该球员其他轮，计算时按异常排除）；
-- 卡13/14 不绑梯台（course_rating_id 为空），用于「难度参数缺失 → 补齐后定向重算」。
INSERT INTO scorecard
(id, tournament_id, player_id, round_no, status, version, expected_holes, total_strokes,
 play_date, course_rating_id, snapshot_json, submitted_at, confirmed_at, last_submitter, created_at, updated_at)
WITH specs(id, tid, pid, rn, pdate, adj, rating) AS (
    SELECT 5,  3, 1, 1, DATE '2026-08-15', 7,  4 UNION ALL
    SELECT 6,  4, 1, 1, DATE '2026-09-12', 8,  4 UNION ALL
    SELECT 7,  4, 1, 2, DATE '2026-09-13', 6,  4 UNION ALL
    SELECT 8,  3, 3, 1, DATE '2026-08-15', 5,  4 UNION ALL
    SELECT 9,  4, 3, 1, DATE '2026-09-12', 5,  4 UNION ALL
    SELECT 10, 4, 3, 2, DATE '2026-09-13', 18, 4 UNION ALL
    SELECT 11, 3, 2, 1, DATE '2026-08-15', 7,  4 UNION ALL
    SELECT 12, 4, 5, 1, DATE '2026-09-12', 7,  4 UNION ALL
    SELECT 13, 6, 10, 1, DATE '2026-09-20', 8, NULL UNION ALL
    SELECT 14, 6, 2, 1, DATE '2026-09-21', 8, NULL UNION ALL
    SELECT 15, 5, 1, 1, DATE '2026-10-04', 3,  4
),
holes_rn AS (
    SELECT h.hole_no, h.par, ROW_NUMBER() OVER (ORDER BY h.hole_no) AS rn
    FROM hole h WHERE h.status = '开放'
),
lines AS (
    SELECT s.id, s.tid, s.pid, s.rn AS round_no, s.pdate, s.adj, s.rating,
           hr.hole_no, hr.par, hr.rn AS hole_idx,
           hr.par
               -- 异常卡（adj=18）：按 3/2/3/3/2/2/3 摊，刻意制造单轮异常差值
               + CASE WHEN s.adj = 18 THEN
                   CASE hr.rn WHEN 1 THEN 3 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 3
                              WHEN 5 THEN 2 WHEN 6 THEN 2 ELSE 3 END
                 -- 普通卡：把超 par 的 adj 杆均匀摊到 7 洞（每洞 base 杆，余杆前 rem 洞各 +1）
                 ELSE (s.adj DIV 7)
                      + CASE WHEN hr.rn <= (s.adj % 7) THEN 1 ELSE 0 END
               END AS strokes
    FROM specs s CROSS JOIN holes_rn hr
)
SELECT l.id, l.tid, l.pid, l.round_no, '已签核', 2, 7, SUM(l.strokes),
       l.pdate, l.rating,
       JSON_OBJECT('roundNo', l.round_no, 'totalStrokes', SUM(l.strokes),
                   'holes', CAST(CONCAT('[', GROUP_CONCAT(
                       JSON_OBJECT('holeNo', l.hole_no, 'par', l.par, 'strokes', l.strokes)
                       ORDER BY l.hole_no SEPARATOR ','), ']') AS JSON)),
       l.pdate - INTERVAL 4 HOUR, l.pdate - INTERVAL 1 HOUR, '记分员小周',
       l.pdate - INTERVAL 1 DAY, l.pdate - INTERVAL 1 HOUR
FROM lines l
GROUP BY l.id, l.tid, l.pid, l.round_no, l.pdate, l.adj, l.rating;

-- 对应逐洞工作行（与快照一致）
INSERT INTO scorecard_hole (scorecard_id, hole_no, par, strokes, updated_at)
WITH specs(id, adj) AS (
    SELECT 5, 7 UNION ALL SELECT 6, 8 UNION ALL SELECT 7, 6 UNION ALL SELECT 8, 5 UNION ALL
    SELECT 9, 5 UNION ALL SELECT 10, 18 UNION ALL SELECT 11, 7 UNION ALL SELECT 12, 8 UNION ALL
    SELECT 13, 8 UNION ALL SELECT 14, 8 UNION ALL SELECT 15, 3
),
holes_rn AS (
    SELECT hole_no, par, ROW_NUMBER() OVER (ORDER BY hole_no) AS rn FROM hole WHERE status = '开放'
)
SELECT s.id, h.hole_no, h.par,
       h.par + CASE WHEN s.adj = 18 THEN
           CASE h.rn WHEN 1 THEN 3 WHEN 2 THEN 2 WHEN 3 THEN 3 WHEN 4 THEN 3
                     WHEN 5 THEN 2 WHEN 6 THEN 2 ELSE 3 END
         ELSE (s.adj DIV 7) + CASE WHEN h.rn <= (s.adj % 7) THEN 1 ELSE 0 END
       END,
       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 DAY
FROM specs s CROSS JOIN holes_rn h;

-- 已签核事件（提交 + 确认各一条）
INSERT INTO scorecard_event
(scorecard_id, action, actor, detail, before_status, after_status, before_version, after_version,
 after_json, created_at)
SELECT c.id, '提交', c.last_submitter, '7 洞齐全，提交后进入待球员确认', '草稿', '待球员确认', 0, 1,
       c.snapshot_json, c.submitted_at
FROM scorecard c WHERE c.id BETWEEN 5 AND 15
UNION ALL
SELECT c.id, '确认', p.name, '球员整卡确认，记分卡正式签核', '待球员确认', '已签核', 1, 2,
       c.snapshot_json, c.confirmed_at
FROM scorecard c JOIN player p ON p.id = c.player_id
WHERE c.id BETWEEN 5 AND 15;

-- 当前进行中赛事默认走练习场 9 洞白梯；历史演示赛事走 A场 7 洞临时场
UPDATE tournament SET course_rating_id = 3 WHERE id = 1;
UPDATE tournament SET course_rating_id = 4 WHERE id IN (3, 4, 5);
