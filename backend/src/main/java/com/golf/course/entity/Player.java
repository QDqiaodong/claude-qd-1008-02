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
 * 球员：下场打球与参加赛事的人员。
 *
 * <p>playerNo 唯一；handicap 差点取值范围 0 ~ 54（建档人工值）。</p>
 *
 * <p>{@code handicapIndex} 等四个指数字段是差点重算服务维护的<b>反范式缓存</b>，
 * 只能由 {@code HandicapService} 写入；球员新增/编辑接口一律忽略页面传入的这些字段，
 * 避免「只在球员表上改一个差点数字」导致历史口径被覆盖。</p>
 */
@Entity
@Table(name = "player")
@Getter
@Setter
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 球员编号，唯一 */
    @Column(name = "player_no", nullable = false, unique = true, length = 16)
    private String playerNo;

    @Column(name = "name", nullable = false, length = 48)
    private String name;

    /** 差点（建档人工整数）：0 ~ 54 */
    @Column(name = "handicap", nullable = false)
    private Integer handicap;

    /** 当前差点指数（一位小数），来自 handicap_index_log 当前链；null 表示尚未算出 */
    @Column(name = "handicap_index")
    private BigDecimal handicapIndex;

    /** 指数状态：CALCULATED 已算出 / INSUFFICIENT 样本不足 / NO_RULE 无生效规则 */
    @Column(name = "index_status", length = 16)
    private String indexStatus;

    /** 当前指数对应的计算截止日（最后一个已签核轮次完赛日） */
    @Column(name = "index_as_of")
    private LocalDate indexAsOf;

    @Column(name = "index_updated_at")
    private LocalDateTime indexUpdatedAt;

    @Column(name = "phone", length = 24)
    private String phone;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

