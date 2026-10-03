package com.beauty.saas.controller;

import com.beauty.saas.common.Result;
import com.beauty.saas.dto.StaffSchedulingRequests.*;
import com.beauty.saas.staff.StaffSchedulingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/staff/scheduling")
@RequiredArgsConstructor
public class StaffSchedulingController {
    private final StaffSchedulingService service;

    @GetMapping("/stores") public Result<List<Map<String,Object>>> stores() { return Result.success(service.stores()); }
    @GetMapping("/staff") public Result<List<Map<String,Object>>> staff(@RequestParam long storeId) { return Result.success(service.staff(storeId)); }
    @GetMapping("/shifts") public Result<List<Map<String,Object>>> shifts() { return Result.success(service.shifts()); }
    @PostMapping("/shifts") public Result<Long> addShift(@Valid @RequestBody ShiftSave input) { return Result.success(service.saveShift(null,input)); }
    @PutMapping("/shifts/{id}") public Result<Long> editShift(@PathVariable long id, @Valid @RequestBody ShiftSave input) { return Result.success(service.saveShift(id,input)); }
    @DeleteMapping("/shifts/{id}") public Result<Void> deleteShift(@PathVariable long id) { service.deleteShift(id); return Result.success(); }
    @PutMapping("/participants") public Result<Void> participants(@Valid @RequestBody ParticipantsSave input) { service.saveParticipants(input); return Result.success(); }
    @GetMapping("/calendar") public Result<Map<String,Object>> calendar(
        @RequestParam long storeId,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(service.calendar(storeId,startDate,endDate));
    }
    @PutMapping("/assignments") public Result<Void> assignment(@Valid @RequestBody AssignmentSave input) { service.saveAssignment(input); return Result.success(); }
    @PostMapping("/copy-week") public Result<Integer> copyWeek(@Valid @RequestBody CopyWeek input) { return Result.success(service.copyPreviousWeek(input)); }
}
