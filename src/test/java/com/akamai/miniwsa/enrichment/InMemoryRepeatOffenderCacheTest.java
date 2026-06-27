package com.akamai.miniwsa.enrichment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRepeatOffenderCacheTest {

    private InMemoryRepeatOffenderCache cache;

    @BeforeEach
    void setUp() {
        cache = new InMemoryRepeatOffenderCache(10, 5, 10000);
    }

    @Test
    void belowThreshold_shouldNotBeRepeatOffender() {
        String ip = "10.0.0.1";
        Instant now = Instant.now();
        // Record 4 hits (threshold is 5)
        for (int i = 0; i < 4; i++) {
            cache.record(ip, now.minusSeconds(60 - i));
        }
        assertThat(cache.isRepeatOffender(ip, now)).isFalse();
    }

    @Test
    void atThreshold_shouldBeRepeatOffender() {
        String ip = "10.0.0.2";
        Instant now = Instant.now();
        // Record exactly 5 hits within the 10-minute window
        for (int i = 0; i < 5; i++) {
            cache.record(ip, now.minusSeconds(30 + i));
        }
        assertThat(cache.isRepeatOffender(ip, now)).isTrue();
    }

    @Test
    void hitsOutsideWindow_shouldNotCount() {
        String ip = "10.0.0.3";
        Instant now = Instant.now();
        // Record 5 hits, but all older than 10 minutes
        for (int i = 0; i < 5; i++) {
            cache.record(ip, now.minusSeconds(700 + i));
        }
        assertThat(cache.isRepeatOffender(ip, now)).isFalse();
    }

    @Test
    void mixedWindowHits_shouldOnlyCountRecent() {
        String ip = "10.0.0.4";
        Instant now = Instant.now();
        // 3 old hits (outside window) + 4 recent hits = 4 recent → not repeat offender
        for (int i = 0; i < 3; i++) {
            cache.record(ip, now.minusSeconds(700 + i));
        }
        for (int i = 0; i < 4; i++) {
            cache.record(ip, now.minusSeconds(60 + i));
        }
        assertThat(cache.isRepeatOffender(ip, now)).isFalse();
    }

    @Test
    void differentIps_shouldNotAffectEachOther() {
        Instant now = Instant.now();
        // Fill ip1 to threshold
        for (int i = 0; i < 5; i++) {
            cache.record("192.168.1.1", now.minusSeconds(i + 1));
        }
        // ip2 has no hits
        assertThat(cache.isRepeatOffender("192.168.1.1", now)).isTrue();
        assertThat(cache.isRepeatOffender("192.168.1.2", now)).isFalse();
    }

    @Test
    void isRepeatOffenderShouldNotRecordTheCheck() {
        String ip = "10.0.0.5";
        Instant now = Instant.now();
        // isRepeatOffender should be read-only; calling it shouldn't add to count
        for (int i = 0; i < 3; i++) {
            cache.isRepeatOffender(ip, now);
        }
        assertThat(cache.isRepeatOffender(ip, now)).isFalse();
    }
}
