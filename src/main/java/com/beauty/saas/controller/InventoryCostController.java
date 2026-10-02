package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.IamRequests.Page;
import com.beauty.saas.dto.InventoryCostRequests.*;
import com.beauty.saas.inventory.InventoryCostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryCostController {
    private final InventoryCostService service;

    @GetMapping("/cost-accounting")
    public Result<Map<String, Object>> accounting(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(required = false) String brand,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) Long departmentId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(service.accounting(new CostQuery(page, pageSize, keyword, brand, category, departmentId, startDate, endDate)));
    }

    @GetMapping("/cost-accounting/{inventoryId}/details")
    public Result<Map<String, Object>> detail(
        @PathVariable long inventoryId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(service.detail(new CostDetailQuery(inventoryId, page, pageSize, startDate, endDate)));
    }

    @GetMapping("/cost-adjustments")
    public Result<Page<Map<String, Object>>> adjustments(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int pageSize,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(required = false) Long departmentId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(service.adjustments(new CostAdjustmentQuery(page, pageSize, keyword, departmentId, startDate, endDate)));
    }

    @PostMapping("/cost-adjustments")
    public Result<Long> saveAdjustment(@Valid @RequestBody CostAdjustmentSave input) {
        return Result.success(service.saveAdjustment(input));
    }
}
