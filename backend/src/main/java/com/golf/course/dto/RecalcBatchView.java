package com.golf.course.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 全量重算分批执行视图：批次状态 + 失败明细。
 */
public record RecalcBatchView(
        Long id,
        String batchNo,
        String batchType,
        String status,
        LocalDate dateFrom,
        LocalDate dateTo,
        Long scopePlayerId,
        Long scopeRatingId,
        Integer totalPlayers,
        Integer successPlayers,
        Integer failedPlayers,
        Integer totalLogs,
        Integer failCount,
        String failDetail,
        String startedAt,
        String finishedAt,
        List<FailureView> failures) {

    public record FailureView(
            Long id,
            Long playerId,
            String playerName,
            LocalDate asOfDate,
            String stage,
            String errorMessage) {
    }
}
