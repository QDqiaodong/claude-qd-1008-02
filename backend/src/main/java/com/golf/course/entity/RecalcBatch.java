package com.golf.course.entity;

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
 * 差点重算批次：全量 / 定向重算各一行，按赛事日期区间分批执行。
 *
 * <p>状态：{@code 排队中} → {@code 执行中} → {@code 已完成} / {@code 部分失败} / {@code 失败}。
 * 全量重算以「赛事（轮次）日期」切批：每批一个日期区间，互不覆盖；
 * 个别球员失败只记录失败明细、不回滚整批（最终状态部分失败，可按失败项再触发）。</p>
 */
@Entity
@Table(name = "recalc_batch")
@Getter
@Setter
public class RecalcBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 批次编号，唯一 */
    @Column(name = "batch_no", nullable = false, unique = true, length = 24)
    private String batchNo;

    /** FULL 全量 / TARGET 定向 */
    @Column(name = "batch_type", nullable = false, length = 16)
    private String batchType;

    /** 排队中 / 执行中 / 已完成 / 部分失败 / 失败 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "date_from")
    private LocalDate dateFrom;

    @Column(name = "date_to")
    private LocalDate dateTo;

    /** 定向重算：指定球员（可空） */
    @Column(name = "scope_player_id")
    private Long scopePlayerId;

    /** 定向重算：指定梯台（补齐该梯台参数后，重算所有绑定该梯台的球员） */
    @Column(name = "scope_rating_id")
    private Long scopeRatingId;

    @Column(name = "total_players", nullable = false)
    private Integer totalPlayers;

    @Column(name = "success_players", nullable = false)
    private Integer successPlayers;

    @Column(name = "failed_players", nullable = false)
    private Integer failedPlayers;

    /** 本次产生的计算日志条数 */
    @Column(name = "total_logs", nullable = false)
    private Integer totalLogs;

    @Column(name = "fail_count", nullable = false)
    private Integer failCount;

    /** 失败摘要（明细见 recalc_batch_failure） */
    @Column(name = "fail_detail", length = 1024)
    private String failDetail;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
