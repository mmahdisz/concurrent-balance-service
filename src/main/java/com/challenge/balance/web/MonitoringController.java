package com.challenge.balance.web;

import com.challenge.balance.monitoring.MonitoringService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MonitoringController {
    private final MonitoringService service;
    public MonitoringController(MonitoringService service) { this.service = service; }

    @GetMapping("/api/monitoring")
    public ResponseEntity<MonitoringService.Report> snapshot() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.snapshot());
    }
}
