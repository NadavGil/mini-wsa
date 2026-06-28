package com.akamai.miniwsa.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configures the async thread pool used by {@code EventIngestionService.ingestAsync()}.
 *
 * <p>Decoupling the HTTP response from the DB write is the core architectural fix
 * for the synchronous-ingestion bottleneck. With a synchronous design, throughput
 * is hard-capped at {@code db_connections × 1000 / write_latency_ms} — around
 * 4,000 events/sec with the default pool. With async, the HTTP thread returns
 * immediately and the bounded worker pool absorbs burst traffic.
 *
 * <p>Backpressure: {@code CallerRunsPolicy} blocks the Tomcat thread when the
 * queue is full, applying natural backpressure to the caller rather than silently
 * dropping events. Production systems would replace this with a Kafka topic for
 * true decoupling and durability.
 */
@Configuration
@EnableAsync
public class AsyncIngestionConfig {

    @Bean("ingestionExecutor")
    public Executor ingestionExecutor(
            @Value("${wsa.ingestion.async.core-pool-size:4}") int corePoolSize,
            @Value("${wsa.ingestion.async.max-pool-size:16}") int maxPoolSize,
            @Value("${wsa.ingestion.async.queue-capacity:1000}") int queueCapacity) {

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("wsa-ingest-");
        // CallerRunsPolicy: when the queue is full, the calling Tomcat thread runs the task.
        // This propagates backpressure upstream instead of dropping events.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
