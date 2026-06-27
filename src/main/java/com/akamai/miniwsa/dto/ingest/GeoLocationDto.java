package com.akamai.miniwsa.dto.ingest;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class GeoLocationDto {
    private String country;
    private String city;
}
