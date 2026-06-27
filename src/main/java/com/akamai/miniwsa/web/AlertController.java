package com.akamai.miniwsa.web;

import com.akamai.miniwsa.dto.alerts.AlertEvaluationResult;
import com.akamai.miniwsa.dto.alerts.AlertRuleRequest;
import com.akamai.miniwsa.dto.alerts.AlertRuleResponse;
import com.akamai.miniwsa.service.AlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/alerts")
@RequiredArgsConstructor
public class AlertController {

    private final AlertService alertService;

    /**
     * POST /v1/alerts/define
     * Defines a new alert rule.
     */
    @PostMapping("/define")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertRuleResponse define(@Valid @RequestBody AlertRuleRequest request) {
        return alertService.define(request);
    }

    /**
     * GET /v1/alerts/evaluate
     * Evaluates all defined alert rules against current event data.
     */
    @GetMapping("/evaluate")
    public List<AlertEvaluationResult> evaluate() {
        return alertService.evaluateAll();
    }
}
