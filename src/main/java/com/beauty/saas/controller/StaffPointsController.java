package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.StaffPointsRequests.PointSave;
import com.beauty.saas.staff.StaffPointsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/staff/points")
@RequiredArgsConstructor
public class StaffPointsController {
    private final StaffPointsService service;

    @GetMapping("/stores") public Result<List<Map<String, Object>>> stores() { return Result.success(service.stores()); }
    @GetMapping("/staff") public Result<List<Map<String, Object>>> staff() { return Result.success(service.staff()); }
    @GetMapping("/rules") public Result<List<Map<String, Object>>> rules() { return Result.success(service.rules()); }

    @GetMapping("/records")
    public Result<Map<String, Object>> records(@RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to,
                                               @RequestParam(required = false) Long storeId,
                                               @RequestParam(required = false) String pointItem,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(service.records(from, to, storeId, pointItem, status, page, pageSize));
    }

    @PostMapping
    public Result<Long> create(@Valid @RequestBody PointSave input) { return Result.success(service.save(null, input)); }

    @PutMapping("/{id}")
    public Result<Long> update(@PathVariable long id, @Valid @RequestBody PointSave input) { return Result.success(service.save(id, input)); }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.success(); }
}
