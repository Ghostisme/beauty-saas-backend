package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.StaffAttendanceRequests.*;
import com.beauty.saas.staff.StaffAttendanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/staff/attendance")
@RequiredArgsConstructor
public class StaffAttendanceController {
    private final StaffAttendanceService service;

    @GetMapping("/stores") public Result<List<Map<String,Object>>> stores() { return Result.success(service.stores()); }
    @GetMapping("/staff") public Result<List<Map<String,Object>>> staff() { return Result.success(service.staff()); }
    @GetMapping("/rules") public Result<List<Map<String,Object>>> rules() { return Result.success(service.rules()); }
    @PostMapping("/rules/conflicts") public Result<List<Map<String,Object>>> conflicts(@Valid @RequestBody RuleSave input) { return Result.success(service.conflicts(input, null)); }
    @PostMapping("/rules") public Result<Long> add(@Valid @RequestBody RuleSave input) { return Result.success(service.save(null, input)); }
    @PutMapping("/rules/{id}") public Result<Long> edit(@PathVariable long id, @Valid @RequestBody RuleSave input) { return Result.success(service.save(id, input)); }
    @DeleteMapping("/rules/{id}") public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.success(); }
}
