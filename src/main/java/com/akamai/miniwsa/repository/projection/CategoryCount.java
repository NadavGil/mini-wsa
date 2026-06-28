package com.akamai.miniwsa.repository.projection;

import com.akamai.miniwsa.domain.AttackCategory;

/**
 * Projection for the batch alert-evaluation query.
 * Maps one row of {@code GROUP BY rule_category} to a category + count pair.
 */
public interface CategoryCount {
    AttackCategory getCategory();
    long getCount();
}
