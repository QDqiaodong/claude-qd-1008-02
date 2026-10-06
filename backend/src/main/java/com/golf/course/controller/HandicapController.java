package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.dto.HandicapProfile;
import com.golf.course.entity.RecalcBatch;
import com.golf.course.service.HandicapService;
import com.golf.course.service.RecalcService;

/**
 * 球员差点指数：画像查询（当前指数 / 最近变化 / 采用与排除轮次 / 当时规则）
 * 与重算触发（全量按赛事日期分批；定向用于补齐梯台参数后只重算受影响球员）。
 */
@RestController
@RequestMapping("/api/handicap")
public class HandicapController {

    private final HandicapService handicapService;
    private final RecalcService recalcService;

    public HandicapController(HandicapService handicapService, RecalcService recalcService) {
        this.handicapService = handicapService;
        this.recalcService = recalcService;
    }

    /** 球员差点画像：当前指数、最近变化、当前节点采用/排除轮次、缺参数轮、当前链历史。 */
    @GetMapping("/players/{playerId}/profile")
    public ApiResponse<HandicapProfile> profile(@PathVariable Long playerId) {
        return ApiResponse.success(handicapService.profile(playerId));
    }

    /** 含被作废旧链的全量历史（历史口径追溯）。 */
    @GetMapping("/players/{playerId}/history")
    public ApiResponse<List<HandicapProfile.HistoryNode>> history(@PathVariable Long playerId) {
        return ApiResponse.success(handicapService.fullHistory(playerId));
    }

    /** 全量重算：按赛事日期分批；不传日期 = 全量。异步执行，返回排队中的批次。 */
    @PostMapping("/recalc/full")
    public ApiResponse<RecalcBatch> fullRecalc(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        RecalcBatch batch = recalcService.createFullBatch(parse(from), parse(to));
        recalcService.runBatch(batch.getId());
        return ApiResponse.success("全量重算批次已创建，后台按赛事日期分批执行", batch);
    }

    /**
     * 定向重算：补齐梯台参数后只重算受影响球员。
     * playerId 指定单球员；ratingId 重算所有绑定该梯台的球员。
     */
    @PostMapping("/recalc/target")
    public ApiResponse<RecalcBatch> targetRecalc(@RequestParam(required = false) Long playerId,
                                                 @RequestParam(required = false) Long ratingId) {
        RecalcBatch batch = recalcService.createTargetBatch(playerId, ratingId);
        recalcService.runBatch(batch.getId());
        return ApiResponse.success("定向重算批次已创建，后台执行", batch);
    }

    private java.time.LocalDate parse(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(s);
        } catch (java.time.DateTimeException e) {
            throw new com.golf.course.common.BizException("日期格式应为 yyyy-MM-dd：" + s);
        }
    }
}
