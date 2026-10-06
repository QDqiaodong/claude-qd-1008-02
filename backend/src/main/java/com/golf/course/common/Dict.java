package com.golf.course.common;

import java.util.List;

/**
 * 高尔夫球场词典与业务规则参数表：球洞状态、开球时间状态、球员差点阈值、
 * 赛事状态流转、成绩杆数与标准杆对比口径，以及各类阈值。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 球洞状态 ----------------
    /** 开放 → 维护 → 开放；封闭为人工停用终态，可从维护进入 */
    public static final List<String> HOLE_STATUSES = List.of("开放", "维护", "封闭");

    // ---------------- 开球时间状态 ----------------
    public static final List<String> TEE_STATUSES = List.of("可订", "已订");

    // ---------------- 球员差点 ----------------
    /** 差点取值范围 0 ~ 54（USGA 上限 54） */
    public static final int HANDICAP_MIN = 0;
    public static final int HANDICAP_MAX = 54;

    // ---------------- 赛事状态：报名中 → 进行中 → 已结束 ----------------
    public static final List<String> TOURNAMENT_FLOW = List.of("报名中", "进行中", "已结束");

    public static final List<String> TOURNAMENT_STATUSES = List.of("报名中", "进行中", "已结束");

    // ---------------- 成绩 ----------------
    /** 单洞最少杆数（一杆起） */
    public static final int MIN_STROKES = 1;

    // ---------------- 记分卡签核闭环 ----------------
    /** 记分卡状态：草稿 → 待球员确认 → 争议中 →（回到待球员确认）→ 已签核 */
    public static final String CARD_DRAFT = "草稿";
    public static final String CARD_PENDING = "待球员确认";
    public static final String CARD_DISPUTED = "争议中";
    public static final String CARD_SIGNED = "已签核";

    public static final List<String> SCORECARD_STATUSES =
            List.of(CARD_DRAFT, CARD_PENDING, CARD_DISPUTED, CARD_SIGNED);

    /** 流转事件动作 */
    public static final String ACTION_SUBMIT = "提交";
    public static final String ACTION_CONFIRM = "确认";
    public static final String ACTION_DISPUTE = "争议";
    public static final String ACTION_UPHOLD = "维持";
    public static final String ACTION_ADJUST = "修正";

    // ---------------- 差点指数 ----------------
    /** 计算日志状态 */
    public static final String INDEX_CALCULATED = "CALCULATED";
    public static final String INDEX_INSUFFICIENT = "INSUFFICIENT";
    public static final String INDEX_NO_RULE = "NO_RULE";

    /** 计算链 */
    public static final String CHAIN_LIVE = "LIVE";
    public static final String CHAIN_SUPERSEDED = "SUPERSEDED";

    /** 触发方式 */
    public static final String TRIGGER_SIGNOFF = "SIGNOFF";
    public static final String TRIGGER_TARGET = "TARGET_RECALC";
    public static final String TRIGGER_FULL = "FULL_RECALC";
    public static final String TRIGGER_BOOTSTRAP = "BOOTSTRAP";

    /** 批次类型 / 状态 */
    public static final String BATCH_FULL = "FULL";
    public static final String BATCH_TARGET = "TARGET";
    public static final String BATCH_QUEUED = "排队中";
    public static final String BATCH_RUNNING = "执行中";
    public static final String BATCH_DONE = "已完成";
    public static final String BATCH_PARTIAL = "部分失败";
    public static final String BATCH_FAILED = "失败";

    /** 轮次排除原因（同时作为页面展示文案 key） */
    public static final String EX_RATING_MISSING = "难度参数缺失";
    public static final String EX_ANOMALY = "单轮差值异常（高出窗口最佳差值超规则阈值，疑似录入错误）";
    public static final String EX_RANK = "选轮排名未入选";
    public static final String EX_INSUFFICIENT = "样本不足未出指数";

    /** 坡度难度值合法区间（USGA：55 ~ 155） */
    public static final int SLOPE_MIN = 55;
    public static final int SLOPE_MAX = 155;

    // ---------------- 取值方法 ----------------

    public static boolean isValidHoleStatus(String status) {
        return status != null && HOLE_STATUSES.contains(status);
    }

    public static boolean isValidTeeStatus(String status) {
        return status != null && TEE_STATUSES.contains(status);
    }

    /** 差点必须落在 0 ~ 54 之间（含端点） */
    public static boolean isValidHandicap(Integer handicap) {
        return handicap != null && handicap >= HANDICAP_MIN && handicap <= HANDICAP_MAX;
    }

    public static boolean isValidTournamentStatus(String status) {
        return status != null && TOURNAMENT_STATUSES.contains(status);
    }

    /** 赛事状态只能逐级推进：报名中 → 进行中 → 已结束 */
    public static boolean canProgressTournament(String from, String to) {
        int i = TOURNAMENT_FLOW.indexOf(from);
        return i >= 0 && i + 1 < TOURNAMENT_FLOW.size() && TOURNAMENT_FLOW.get(i + 1).equals(to);
    }
}
