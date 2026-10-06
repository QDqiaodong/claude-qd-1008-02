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
 * 重算失败明细：批次内逐球员 × 截止日记录失败阶段与错误信息。
 * 失败不回滚整批，管理员可据此定向重试，避免一条脏数据让全量重算整体报废。
 */
@Entity
@Table(name = "recalc_batch_failure")
@Getter
@Setter
public class RecalcBatchFailure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "player_id")
    private Long playerId;

    @Column(name = "as_of_date")
    private LocalDate asOfDate;

    /** 失败阶段：REBUILD_ROUNDS / CALC_CHAIN 等 */
    @Column(name = "stage", length = 32)
    private String stage;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
