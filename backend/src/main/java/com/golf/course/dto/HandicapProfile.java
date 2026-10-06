package com.golf.course.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 球员差点画像：球员页一次拿到当前指数、最近变化，以及当前指数所采用/排除的轮次与规则。
 */
public record HandicapProfile(
        Long playerId,
        String playerName,
        BigDecimal currentIndex,
        String indexStatus,
        LocalDate asOfDate,
        BigDecimal previousIndex,
        BigDecimal lastChange,
        Long ruleId,
        String ruleName,
        LocalDate ruleEffectiveDate,
        String cappedNote,
        List<HistoryNode> history,
        List<RoundLine> currentRounds,
        List<MissingRound> missingRounds) {

    /** 历史指数节点（当前链回放；ruleVersion 标注当时用的规则，保证口径可追溯）。 */
    public record HistoryNode(
            Long logId,
            LocalDate asOfDate,
            String status,
            BigDecimal rawIndex,
            BigDecimal finalIndex,
            BigDecimal previousIndex,
            String triggerType,
            Long ruleId,
            String ruleName,
            LocalDate ruleEffectiveDate,
            String chain,
            String cappedNote,
            List<RoundLine> rounds) {
    }

    /** 轮次采用/排除明细。 */
    public record RoundLine(
            Long handicapRoundId,
            Long tournamentId,
            String tournamentName,
            Integer roundNo,
            LocalDate playDate,
            Integer totalStrokes,
            Integer totalPar,
            String teeName,
            BigDecimal diffValue,
            boolean included,
            Integer rankNo,
            String exclusionReason) {
    }

    /** 缺难度参数的已签核轮：页面提示补齐梯台后可定向重算。 */
    public record MissingRound(
            Long scorecardId,
            Long tournamentId,
            String tournamentName,
            Integer roundNo,
            LocalDate playDate,
            Integer totalStrokes) {
    }
}
