package com.akamai.miniwsa.web;

import com.akamai.miniwsa.dto.alerts.AlertEvaluationResult;
import com.akamai.miniwsa.dto.alerts.AlertRuleRequest;
import com.akamai.miniwsa.dto.alerts.AlertRuleResponse;
import com.akamai.miniwsa.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/alerts")
@RequiredArgsConstructor
@Tag(name = "Alerts", description = "Alert rule management and evaluation")
public class AlertController {

    private final AlertService alertService;

    @PostMapping("/define")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Define an alert rule",
        description = "Creates a new alert rule that fires when the event count for a given " +
                      "attack category exceeds a threshold within a rolling time window."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Alert rule created"),
        @ApiResponse(responseCode = "400", description = "Validation failure",
                     content = @Content(schema = @Schema(ref = "#/components/schemas/ApiError"))),
        @ApiResponse(responseCode = "409", description = "Maximum number of alert rules reached",
                     content = @Content(schema = @Schema(ref = "#/components/schemas/ApiError")))
    })
    public AlertRuleResponse define(@Valid @RequestBody AlertRuleRequest request) {
        return alertService.define(request);
    }

    @GetMapping("/evaluate")
    @Operation(
        summary = "Evaluate all alert rules",
        description = "Evaluates every defined alert rule against current event data and returns " +
                      "the firing status and observed event count for each rule. " +
                      "Uses a single batched DB query per distinct window size (not N+1)."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of alert evaluation results")
    })
    public List<AlertEvaluationResult> evaluate() {
        return alertService.evaluateAll();
    }
}
