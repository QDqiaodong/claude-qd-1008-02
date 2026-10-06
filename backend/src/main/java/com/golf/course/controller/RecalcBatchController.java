package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.dto.RecalcBatchView;
import com.golf.course.entity.RecalcBatch;
import com.golf.course.service.RecalcService;

/**
 * 重算批次查询：批次列表（按时间倒序）、批次详情（含逐球员失败明细）。
 */
@RestController
@RequestMapping("/api/handicap/recalc-batches")
public class RecalcBatchController {

    private final RecalcService recalcService;

    public RecalcBatchController(RecalcService recalcService) {
        this.recalcService = recalcService;
    }

    @GetMapping
    public ApiResponse<List<RecalcBatch>> list() {
        return ApiResponse.success(recalcService.listBatches());
    }

    @GetMapping("/{id}")
    public ApiResponse<RecalcBatchView> get(@PathVariable Long id) {
        return ApiResponse.success(recalcService.getBatchView(id));
    }
}
