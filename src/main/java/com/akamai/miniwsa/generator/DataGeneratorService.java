package com.akamai.miniwsa.generator;

import com.akamai.miniwsa.domain.ActionType;
import com.akamai.miniwsa.domain.AttackCategory;
import com.akamai.miniwsa.domain.Severity;
import com.akamai.miniwsa.dto.ingest.EventIngestRequest;
import com.akamai.miniwsa.dto.ingest.GeoLocationDto;
import com.akamai.miniwsa.dto.ingest.RuleInfoDto;
import com.akamai.miniwsa.service.EventIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates synthetic WAF event data for demo/test purposes.
 * Only available when the "datagen" Spring profile is active.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DataGeneratorService {

    private final EventIngestionService ingestionService;
    private final Random random = new Random();

    private static final String[] IPS = {
        "10.0.0.1", "10.0.0.2", "10.0.0.3", "192.168.1.100",
        "203.0.113.5", "198.51.100.7", "192.0.2.42"
    };
    private static final String[] PATHS = {
        "/api/users", "/admin/dashboard", "/login", "/api/products",
        "/api/orders", "/v1/search", "/.env", "/wp-admin/", "/api/config"
    };
    private static final String[] METHODS = {"GET", "POST", "PUT", "DELETE", "PATCH"};
    private static final String[] COUNTRIES = {"US", "CN", "RU", "DE", "GB", "FR", "BR"};
    private static final AttackCategory[] CATEGORIES = AttackCategory.values();
    private static final ActionType[] ACTIONS = ActionType.values();
    private static final Severity[] SEVERITIES = Severity.values();

    /**
     * Generates and ingests {@code count} synthetic events in batches of 100.
     */
    public int generate(int count, Long configId) {
        int total = 0;
        int batchSize = 100;

        for (int i = 0; i < count; i += batchSize) {
            int thisBatch = Math.min(batchSize, count - i);
            List<EventIngestRequest> batch = new ArrayList<>(thisBatch);

            for (int j = 0; j < thisBatch; j++) {
                batch.add(buildRandom(configId));
            }

            List<String> ids = ingestionService.ingest(batch);
            total += ids.size();
        }

        log.info("DataGenerator: ingested {} synthetic events for configId={}", total, configId);
        return total;
    }

    private EventIngestRequest buildRandom(Long configId) {
        AttackCategory cat = CATEGORIES[random.nextInt(CATEGORIES.length)];
        Severity sev = SEVERITIES[random.nextInt(SEVERITIES.length)];
        ActionType action = ACTIONS[random.nextInt(ACTIONS.length)];

        RuleInfoDto rule = new RuleInfoDto(
                "RULE-" + (1000 + random.nextInt(9000)),
                "Synthetic rule for " + cat,
                "Auto-generated rule message",
                sev,
                cat
        );

        GeoLocationDto geo = new GeoLocationDto(
                COUNTRIES[random.nextInt(COUNTRIES.length)],
                null
        );

        return new EventIngestRequest(
                UUID.randomUUID().toString(),
                Instant.now().minusSeconds(random.nextInt(3600)),
                configId != null ? configId : (long) (1000 + random.nextInt(9000)),
                "POL-" + (100 + random.nextInt(900)),
                IPS[random.nextInt(IPS.length)],
                "waf-demo.example.com",
                PATHS[random.nextInt(PATHS.length)],
                METHODS[random.nextInt(METHODS.length)],
                200 + random.nextInt(400),
                "Mozilla/5.0 (synthetic)",
                rule,
                action,
                geo,
                (long) random.nextInt(4096),
                (long) random.nextInt(8192)
        );
    }
}
