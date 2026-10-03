package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.StaffSopRequests.*;
import com.beauty.saas.staff.StaffSopService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/staff/sop")
@RequiredArgsConstructor
public class StaffSopController {
    private final StaffSopService service;

    @GetMapping("/positions") public Result<List<Map<String,Object>>> positions() { return Result.success(service.availablePositions()); }
    @GetMapping("/rules") public Result<List<Map<String,Object>>> rules() { return Result.success(service.rules()); }
    @PostMapping("/rules") public Result<Long> create(@Valid @RequestBody RuleSave input) { return Result.success(service.save(null,input)); }
    @PutMapping("/rules/{id}") public Result<Long> update(@PathVariable long id,@Valid @RequestBody RuleSave input) { return Result.success(service.save(id,input)); }
    @DeleteMapping("/rules/{id}") public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.success(); }
    @GetMapping("/monthly") public Result<Map<String,Object>> monthly(@RequestParam long storeId,@RequestParam String month,@RequestParam(required=false) Long positionId) {
        return Result.success(service.monthly(storeId,month,positionId));
    }
    @PutMapping("/checks") public Result<Void> check(@Valid @RequestBody CheckSave input) { service.check(input); return Result.success(); }
}
