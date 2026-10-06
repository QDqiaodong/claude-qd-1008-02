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
-- handicap 差点取值范围 0 ~ 54
CREATE TABLE player (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    player_no VARCHAR(16) NOT NULL UNIQUE,
    name VARCHAR(48) NOT NULL,
    handicap INT NOT NULL,
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
