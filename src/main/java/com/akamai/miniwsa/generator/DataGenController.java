package com.akamai.miniwsa.generator;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Dev-only endpoint to seed synthetic WAF events.
 * Only active when profile "datagen" or "h2" is active — never in production.
 */
@RestController
@RequestMapping("/dev/generate")
@RequiredArgsConstructor
@Profile({"h2", "datagen"})
public class DataGenController {

    private final DataGeneratorService generatorService;

    /**
     * POST /dev/generate?count=200&configId=1001
     * Generates synthetic WAF events and ingests them directly.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> generate(
            @RequestParam(defaultValue = "100") int count,
            @RequestParam(required = false) Long configId) {

        if (count < 1 || count > 10_000) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "count must be between 1 and 10000"));
        }

        int ingested = generatorService.generate(count, configId);
        return ResponseEntity.ok(Map.of(
                "requested", count,
                "ingested", ingested,
                "configId", configId != null ? configId : "random"
        ));
    }
}
