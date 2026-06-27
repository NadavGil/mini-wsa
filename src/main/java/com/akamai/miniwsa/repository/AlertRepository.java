package com.akamai.miniwsa.repository;

import com.akamai.miniwsa.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<AlertRule, String> {}
