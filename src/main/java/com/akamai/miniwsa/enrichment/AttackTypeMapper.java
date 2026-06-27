package com.akamai.miniwsa.enrichment;

import com.akamai.miniwsa.domain.AttackCategory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AttackTypeMapper {

    private static final Map<AttackCategory, String> MAP = Map.of(
            AttackCategory.INJECTION,          "SQL/Command Injection",
            AttackCategory.XSS,                "Cross-Site Scripting",
            AttackCategory.PROTOCOL_VIOLATION, "Protocol Anomaly",
            AttackCategory.DATA_LEAKAGE,       "Data Exfiltration",
            AttackCategory.BOT,                "Bot Activity",
            AttackCategory.DOS,                "Denial of Service",
            AttackCategory.RATE_LIMIT,         "Rate Limiting"
    );

    public String map(AttackCategory category) {
        if (category == null) return "Unknown";
        return MAP.getOrDefault(category, "Unknown");
    }
}
