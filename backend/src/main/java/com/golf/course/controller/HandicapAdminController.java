package com.golf.course.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.golf.course.common.ApiResponse;
import com.golf.course.entity.CourseRating;
import com.golf.course.entity.HandicapRule;
import com.golf.course.service.HandicapAdminService;

/**
 * 管理员维护：球场难度参数（梯台）与带生效日期的差点计算规则。
 * 已生效规则锁定不可改，需新建更晚生效规则；梯台补齐后可走重算接口定向重算受影响球员。
 */
@RestController
@RequestMapping("/api/handicap/admin")
public class HandicapAdminController {

    private final HandicapAdminService adminService;

    public HandicapAdminController(HandicapAdminService adminService) {
        this.adminService = adminService;
    }

    // ---------------- 梯台 ----------------

    @GetMapping("/ratings")
    public ApiResponse<List<CourseRating>> listRatings() {
        return ApiResponse.success(adminService.listRatings());
    }

    @GetMapping("/ratings/{id}")
    public ApiResponse<CourseRating> getRating(@PathVariable Long id) {
        return ApiResponse.success(adminService.getRating(id));
    }

    @PostMapping("/ratings")
    public ApiResponse<CourseRating> createRating(@RequestBody CourseRating input) {
        return ApiResponse.success("梯台难度参数已建立（旧记分卡可补绑后定向重算）",
                adminService.createRating(input));
    }

    @PutMapping("/ratings/{id}")
    public ApiResponse<CourseRating> updateRating(@PathVariable Long id, @RequestBody CourseRating input) {
        return ApiResponse.success("梯台参数已更新，请对受影响球员发起定向重算",
                adminService.updateRating(id, input));
    }

    // ---------------- 规则 ----------------

    @GetMapping("/rules")
    public ApiResponse<List<HandicapRule>> listRules() {
        return ApiResponse.success(adminService.listRules());
    }

    @GetMapping("/rules/{id}")
    public ApiResponse<HandicapRule> getRule(@PathVariable Long id) {
        return ApiResponse.success(adminService.getRule(id));
    }

    @PostMapping("/rules")
    public ApiResponse<HandicapRule> createRule(@RequestBody HandicapRule input) {
        return ApiResponse.success("规则已建立，自生效日起的计算才会采用",
                adminService.createRule(input));
    }

    @PutMapping("/rules/{id}")
    public ApiResponse<HandicapRule> updateRule(@PathVariable Long id, @RequestBody HandicapRule input) {
        return ApiResponse.success("规则已更新（仅未生效规则可改）", adminService.updateRule(id, input));
    }

    @PostMapping("/rules/{id}/toggle")
    public ApiResponse<HandicapRule> toggleRule(@PathVariable Long id,
                                                @RequestParam boolean enabled) {
        return ApiResponse.success(enabled ? "规则已启用" : "规则已停用（历史日志保留引用）",
                adminService.toggleRule(id, enabled));
    }
}
