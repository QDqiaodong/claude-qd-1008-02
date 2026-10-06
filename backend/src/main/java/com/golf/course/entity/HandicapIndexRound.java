package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 计算轮次留痕：某次指数计算（{@code handicap_index_log}）窗口内每轮一行。
 *
 * <p>{@link #included}=1 为被采用轮；为 0 时 {@link #exclusionReason} 必须写明原因：
 * 难度参数缺失 / 单轮差值异常（高出窗口最佳差值超规则阈值）/ 选轮排名未入选 / 样本不足未出指数。
 * {@link #diffValue} 是<b>当时</b>采用的差值快照，随日志固化；
 * 之后补参数、改梯台产生的差值变化只体现在重算后的新链，旧行原样保留，保证可追溯。</p>
 */
@Entity
@Table(name = "handicap_index_round")
@Getter
@Setter
public class HandicapIndexRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_id", nullable = false)
    private Long logId;

    @Column(name = "handicap_round_id", nullable = false)
    private Long handicapRoundId;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "play_date", nullable = false)
    private LocalDate playDate;

    @Column(name = "total_strokes", nullable = false)
    private Integer totalStrokes;

    /** 当时差值快照；难度参数缺失时为空 */
    @Column(name = "diff_value", precision = 5, scale = 1)
    private BigDecimal diffValue;

    /** 1=采用，0=排除 */
    @Column(name = "included", nullable = false)
    private Boolean included;

    /** 采用轮在本次窗口内的差值名次（从 1 起），排除轮为空 */
    @Column(name = "rank_no")
    private Integer rankNo;

    /** 排除原因（included=0 时必填） */
    @Column(name = "exclusion_reason", length = 128)
    private String exclusionReason;
}
