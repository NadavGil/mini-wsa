package com.akamai.miniwsa.dto.ingest;

import com.akamai.miniwsa.domain.ActionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EventIngestRequest {

    @NotBlank
    private String eventId;

    @NotNull
    private Instant timestamp;

    @NotNull
    private Long configId;

    private String policyId;

    @NotBlank
    private String clientIp;

    private String hostname;

    @Size(max = 2048)
    private String path;

    private String method;

    @Min(100)
    @Max(599)
    private Integer statusCode;

    @Size(max = 512)
    private String userAgent;

    @NotNull
    @Valid
    private RuleInfoDto rule;

    @NotNull
    private ActionType action;

    @Valid
    private GeoLocationDto geoLocation;

    @Min(0)
    private Long requestSize;

    @Min(0)
    private Long responseSize;
}
