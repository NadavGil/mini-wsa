package com.akamai.miniwsa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeoLocation {

    @Column(name = "geo_country", length = 3)
    private String country;

    @Column(name = "geo_city", length = 100)
    private String city;
}
