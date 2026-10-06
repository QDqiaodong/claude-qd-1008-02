package com.golf.course.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * 差点计算规则（版本化，按生效日切换）。
 *
 * <p>规则不可物理删除——历史差点回放必须找到当时的规则；
 * 生效日唯一：同一天不允许两条规则并存。已生效规则不可再编辑，
 * 只能新建一条更晚生效的规则（新规则只影响生效日之后的计算）。</p>
 *
 * <ul>
 *   <li>{@link #windowRounds}：取最近 N 轮已签核成绩（缺参数轮也占位展示但不带差值）；</li>
 *   <li>{@link #minRounds}：有效样本（差值可算且未判异常）少于该值时显示「样本不足」；</li>
 *   <li>{@link #countTableJson}：样本数 → 采用最低差值个数（WHS 选轮表）；</li>
 *   <li>{@link #maxDiffBelowIndex}：单轮差值高出「窗口内最佳差值」超过该值判异常并排除；</li>
 *   <li>{@link #maxDecreasePerRound}：单次重算指数最多下调多少（上调不限制）；</li>
 *   <li>{@link #holeScoreCap}：逐洞杆数封顶（Net Double Bogey 简化口径），null 表示不封顶。</li>
 * </ul>
 */
@Entity
@Table(name = "handicap_rule")
@Getter
@Setter
public class HandicapRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则编码，唯一 */
    @Column(name = "code", nullable = false, unique = true, length = 24)
    private String code;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 生效日期：计算某截止日时取生效日 ≤ 截止日的最新一条 */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** 采样窗口轮次（最近 N 轮） */
    @Column(name = "window_rounds", nullable = false)
    private Integer windowRounds;

    /** 最少有效样本轮次，不足则样本不足 */
    @Column(name = "min_rounds", nullable = false)
    private Integer minRounds;

    /** 样本数 -> 采用最低差值个数 的 JSON，如 {"3":1,...,"20":10} */
    @Column(name = "count_table_json", nullable = false, length = 512)
    private String countTableJson;

    /** 单轮差值高出窗口最佳差值多少即判异常（防录入错误） */
    @Column(name = "max_diff_below_index", nullable = false, precision = 4, scale = 1)
    private BigDecimal maxDiffBelowIndex;

    /** 单次重算指数下调幅度上限（上调不限） */
    @Column(name = "max_decrease_per_round", nullable = false, precision = 4, scale = 1)
    private BigDecimal maxDecreasePerRound;

    /** 逐洞杆数封顶；null 表示该规则不封顶 */
    @Column(name = "hole_score_cap")
    private Integer holeScoreCap;

    @Column(name = "index_min", nullable = false, precision = 4, scale = 1)
    private BigDecimal indexMin;

    @Column(name = "index_max", nullable = false, precision = 4, scale = 1)
    private BigDecimal indexMax;

    /** 是否启用；停用后新计算不再选中，但历史日志保留引用 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
