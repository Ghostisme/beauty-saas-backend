package com.beauty.saas.controller;

import com.beauty.saas.appointment.AppointmentService;
import com.beauty.saas.common.Result;
import com.beauty.saas.dto.AppointmentRequests.*;
import com.beauty.saas.dto.IamRequests.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/appointments")
@RequiredArgsConstructor
public class AppointmentController {
    private final AppointmentService appointments;

    @GetMapping("/options") public Result<Map<String,Object>> options(@RequestParam(required=false) Long departmentId) {
        return Result.success(appointments.options(departmentId));
    }

    @GetMapping public Result<Page<Map<String,Object>>> list(
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,
        @RequestParam(required=false) Long departmentId, @RequestParam(required=false) String status,
        @RequestParam(required=false) String keyword, @RequestParam(defaultValue="ALL") String searchBy,
        @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="100") int pageSize) {
        return Result.success(appointments.list(new AppointmentQuery(date, startDate, endDate, departmentId, status, keyword, searchBy, page, pageSize)));
    }
    @GetMapping("/settings") public Result<Map<String,Object>> settings(@RequestParam Long departmentId) {
        return Result.success(appointments.settings(departmentId));
    }
    @PutMapping("/settings") public Result<Map<String,Object>> settings(@Valid @RequestBody AppointmentSettingsSave input) {
        return Result.success(appointments.saveSettings(input));
    }
    @PostMapping public Result<Long> create(@Valid @RequestBody AppointmentSave input) { return Result.success(appointments.save(null, input)); }
    @PostMapping("/recurring") public Result<java.util.List<Long>> recurring(@Valid @RequestBody RecurringAppointmentSave input) { return Result.success(appointments.saveRecurring(input)); }
    @PutMapping("/{id}") public Result<Long> update(@PathVariable long id, @Valid @RequestBody AppointmentSave input) { return Result.success(appointments.save(id, input)); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable long id) { appointments.delete(id); return Result.success(); }
}
