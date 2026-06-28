package com.akamai.miniwsa.dto.alerts;

import com.akamai.miniwsa.domain.AttackCategory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleRequest {

    @NotBlank
    private String name;

    @NotNull
    private AttackCategory category;

    @NotNull
    @Min(1)
    @Max(100_000)   // prevents absurdly large thresholds
    private Integer threshold;

    @NotNull
    @Min(1)
    @Max(1440)      // max 24 hours — prevents full-table-scan via 4000-year window
    private Integer windowMinutes;
}
