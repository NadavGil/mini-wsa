package com.akamai.miniwsa.repository.projection;

import com.akamai.miniwsa.domain.AttackCategory;

public interface CategoryAggregation {
    AttackCategory getCategory();
    long getCount();
    double getAvgThreatScore();
}
