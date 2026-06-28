package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.EnrichedEvent;
import com.akamai.miniwsa.repository.projection.ActionAggregation;
import com.akamai.miniwsa.repository.projection.AttackerAggregation;
import com.akamai.miniwsa.repository.projection.CategoryAggregation;
import com.akamai.miniwsa.repository.projection.PathAggregation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface EventRepository extends JpaRepository<EnrichedEvent, String>,
        JpaSpecificationExecutor<EnrichedEvent> {

    // Repeat offender check
    long countByClientIpAndTimestampAfter(String clientIp, Instant cutoff);

    // Stats: total events in window (null configId = all configs)
    @Query("select count(e) from EnrichedEvent e " +
           "where (:configId is null or e.configId = :configId) " +
           "and e.timestamp between :from and :to")
    long countInWindow(@Param("configId") Long configId,
                       @Param("from") Instant from,
                       @Param("to") Instant to);

    // Stats: by category
    @Query("select e.rule.category as category, count(e) as count, avg(e.threatScore) as avgThreatScore " +
           "from EnrichedEvent e " +
           "where (:configId is null or e.configId = :configId) " +
           "and e.timestamp between :from and :to " +
           "group by e.rule.category")
    List<CategoryAggregation> aggregateByCategory(@Param("configId") Long configId,
                                                   @Param("from") Instant from,
                                                   @Param("to") Instant to);

    // Stats: by action
    @Query("select e.action as action, count(e) as count from EnrichedEvent e " +
           "where (:configId is null or e.configId = :configId) " +
           "and e.timestamp between :from and :to " +
           "group by e.action")
    List<ActionAggregation> aggregateByAction(@Param("configId") Long configId,
                                               @Param("from") Instant from,
                                               @Param("to") Instant to);

    // Stats: top attackers (pass PageRequest.of(0, 10) for top-10)
    @Query("select e.clientIp as clientIp, count(e) as count, avg(e.threatScore) as avgThreatScore " +
           "from EnrichedEvent e " +
           "where (:configId is null or e.configId = :configId) " +
           "and e.timestamp between :from and :to " +
           "group by e.clientIp order by count(e) desc")
    List<AttackerAggregation> topAttackers(@Param("configId") Long configId,
                                            @Param("from") Instant from,
                                            @Param("to") Instant to,
                                            Pageable pageable);

    // Stats: top paths
    @Query("select e.path as path, count(e) as count from EnrichedEvent e " +
           "where (:configId is null or e.configId = :configId) " +
           "and e.timestamp between :from and :to " +
           "group by e.path order by count(e) desc")
    List<PathAggregation> topPaths(@Param("configId") Long configId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    Pageable pageable);

    // Alert evaluation: count events of a given category on or after a cutoff (>= inclusive, matches BETWEEN semantics)
    @Query("select count(e) from EnrichedEvent e " +
           "where e.rule.category = :category and e.timestamp >= :from")
    long countByRuleCategoryAndTimestampOnOrAfter(@Param("category") AttackCategory category,
                                                   @Param("from") Instant from);
}
