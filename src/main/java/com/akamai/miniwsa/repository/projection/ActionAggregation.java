package com.akamai.miniwsa.repository.projection;

import com.akamai.miniwsa.domain.ActionType;

public interface ActionAggregation {
    ActionType getAction();
    long getCount();
}
