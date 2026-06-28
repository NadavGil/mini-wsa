package com.akamai.miniwsa.dto.ingest;

import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RuleInfoDto {

    @NotBlank
    private String ruleId;

    @NotBlank
    private String ruleName;

    @Size(max = 1024)
    private String ruleMessage;

    @NotNull
    private Severity severity;

    @NotNull
    private AttackCategory category;
}
