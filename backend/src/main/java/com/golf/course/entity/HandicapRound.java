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
 * 已签核轮次派生层：一张已签核记分卡对应一行，是差点计算的唯一取数口径。
 *
 * <p>由签核动作同步 upsert；定向/全量重算前可整卡批量重建。
 * {@code totalStrokes/totalPar} 取签核快照；未绑梯台时 {@link #diffValue} 为空、
 * {@link #ratingMissing}=1，计算时该轮以「难度参数缺失」排除。</p>
 *
 * <p>性能口径：全量重算按 {@code (play_date, id)} 键集分页扫本派生表，
 * 逐洞汇总在一次 SQL 内完成（见 {@code HandicapRoundRepository#rebuildFromSignedCards}），
 * 十万条逐洞成绩下不会对每个球员反复全表扫描。</p>
 */
@Entity
@Table(name = "handicap_round")
@Getter
@Setter
public class HandicapRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 来源记分卡，一对一 */
    @Column(name = "scorecard_id", nullable = false, unique = true)
    private Long scorecardId;

    @Column(name = "tournament_id", nullable = false)
    private Long tournamentId;

    @Column(name = "player_id", nullable = false)
    private Long playerId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 轮次完赛日（取自记分卡 play_date） */
    @Column(name = "play_date", nullable = false)
    private LocalDate playDate;

    @Column(name = "total_strokes", nullable = false)
    private Integer totalStrokes;

    /** 按规则逐洞封顶后的总杆（规则不封顶时等于 totalStrokes） */
    @Column(name = "adjusted_strokes", nullable = false)
    private Integer adjustedStrokes;

    @Column(name = "total_par", nullable = false)
    private Integer totalPar;

    /** 派生本行时所用的逐洞封顶值（null=未封顶） */
    @Column(name = "hole_cap")
    private Integer holeCap;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "course_rating_id")
    private CourseRating courseRating;

    /** 差点差值（一位小数）；梯台缺失或重建时可空 */
    @Column(name = "diff_value", precision = 5, scale = 1)
    private BigDecimal diffValue;

    /** 1=球场难度参数缺失（旧数据没绑梯台） */
    @Column(name = "rating_missing", nullable = false)
    private Boolean ratingMissing;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
