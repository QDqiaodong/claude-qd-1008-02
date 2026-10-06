package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 差点指数计算日志：每次计算（实时签核 / 定向重算 / 全量重算）追加一行，永不物理删除。
 *
 * <p>链条字段 {@link #chain}：{@code LIVE} 当前链、{@code SUPERSEDED} 被后续重算整体作废；
 * 被同一次重算作废的旧行共享 {@link #supersededBy}（指向重算批次）。
 * 球员表上的当前指数永远取本链最新一行，因此历史指数可按当时规则逐条回放，
 * 不会被新口径改写。</p>
 *
 * <p>{@link #status}：CALCULATED 正常出指数；INSUFFICIENT 有效样本不足；
 * NO_RULE 截止日找不到任何已生效规则。{@link #rawIndex} 为选轮平均（已含 -2 差值口径），
 * {@link #finalIndex} 为叠加「单次下调上限 + 区间钳制」后的结果，统一保留一位小数。</p>
 */
@Entity
@Table(name = "handicap_index_log")
@Getter
@Setter
public class HandicapIndexLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    /** 本次计算截止日：只纳入 play_date ≤ asOfDate 的已签核轮次 */
    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    /** 本次实际采用的规则版本（按生效日选取）；NO_RULE 时为空 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "rule_id")
    private HandicapRule rule;

    /** CALCULATED / INSUFFICIENT / NO_RULE */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 参与窗口的轮次数（含缺参数轮） */
    @Column(name = "sample_count", nullable = false)
    private Integer sampleCount;

    /** 最终采用差值的轮次数 */
    @Column(name = "used_count", nullable = false)
    private Integer usedCount;

    /** 选轮平均（未经下调/钳制），INSUFFICIENT/NO_RULE 时为空 */
    @Column(name = "raw_index", precision = 5, scale = 1)
    private BigDecimal rawIndex;

    /** 最终差点指数（一位小数） */
    @Column(name = "final_index", precision = 5, scale = 1)
    private BigDecimal finalIndex;

    /** 截止日上一节点的最终指数（用于单次下调限制与变化展示） */
    @Column(name = "previous_index", precision = 5, scale = 1)
    private BigDecimal previousIndex;

    /** 异常排除与下调/钳制的文字说明（如「单次下调超 2.0，已限制为 6.9」） */
    @Column(name = "capped_note", length = 255)
    private String cappedNote;

    /** LIVE / SUPERSEDED */
    @Column(name = "chain", nullable = false, length = 16)
    private String chain;

    /** 由哪次重算批次产生；实时签核触发为空 */
    @Column(name = "batch_id")
    private Long batchId;

    /** 触发方式：SIGNOFF 实时签核 / TARGET_RECALC 定向重算 / FULL_RECALC 全量重算 / BOOTSTRAP 启动回填 */
    @Column(name = "trigger_type", nullable = false, length = 24)
    private String triggerType;

    /** 本行被哪次批次作废（SUPERSEDED 时非空） */
    @Column(name = "superseded_by")
    private Long supersededBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
