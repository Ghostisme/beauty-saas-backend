package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.StaffPositionSave;
import com.beauty.saas.staff.StaffPositionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/staff/positions")
@RequiredArgsConstructor
public class StaffPositionController {
    private final StaffPositionService service;

    @GetMapping public Result<List<Map<String,Object>>> list() { return Result.success(service.list()); }
    @PostMapping public Result<Long> create(@Valid @RequestBody StaffPositionSave input) { return Result.success(service.save(null,input)); }
    @PutMapping("/{id}") public Result<Long> update(@PathVariable long id, @Valid @RequestBody StaffPositionSave input) { return Result.success(service.save(id,input)); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable long id) { service.delete(id); return Result.success(); }
}
