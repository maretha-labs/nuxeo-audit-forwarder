/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.config.SinkSettings;

/** A configured sink with its delivery policy and counters. */
public class SinkRuntime implements AutoCloseable {

    private static final Logger log = LogManager.getLogger(SinkRuntime.class);

    public static final String TEST_EVENT_ID = "auditForwarderTest";

    protected final SinkSettings settings;

    protected final AuditSink sink;

    protected final String sourceHost;

    protected final Set<String> events;

    protected final AtomicLong sent = new AtomicLong();

    protected final AtomicLong failedAttempts = new AtomicLong();

    protected final AtomicLong dropped = new AtomicLong();

    protected volatile Instant lastSuccessAt;

    protected volatile Instant lastErrorAt;

    protected volatile String lastError;

    protected volatile boolean failing;

    public SinkRuntime(SinkSettings settings, AuditSink sink, String sourceHost) {
        this.settings = settings;
        this.sink = sink;
        this.sourceHost = sourceHost;
        this.events = settings.events();
    }

    public SinkSettings settings() {
        return settings;
    }

    /** @return true unless the sink narrows the global selection with its own {@code events} list */
    public boolean accepts(AuditEvent event) {
        return events.isEmpty() || events.contains(event.eventId());
    }

    /**
     * Delivers {@code events} in batches of {@code batch.size}. A failed batch is retried with exponential backoff:
     * forever by default, or {@code retry.maxAttempts} times when {@code continueOnFailure=true}, after which it is
     * dropped and counted.
     */
    public void deliver(List<AuditEvent> events) throws InterruptedException {
        int size = settings.batchSize();
        for (int from = 0; from < events.size(); from += size) {
            deliverBatch(events.subList(from, Math.min(events.size(), from + size)));
        }
    }

    protected void deliverBatch(List<AuditEvent> batch) throws InterruptedException {
        Duration delay = settings.initialDelay();
        for (int attempt = 1;; attempt++) {
            try {
                sink.send(batch);
                recordSuccess(batch.size());
                return;
            } catch (SinkException | RuntimeException e) {
                recordFailure(e);
                if (settings.continueOnFailure() && attempt >= settings.maxAttempts()) {
                    dropped.addAndGet(batch.size());
                    log.error("Audit forwarder sink '{}': dropped {} events after {} attempts: {}", settings.name(),
                            batch.size(), attempt, e.getMessage());
                    return;
                }
                log.warn("Audit forwarder sink '{}': attempt {} failed, retrying in {} ms: {}", settings.name(),
                        attempt, delay.toMillis(), e.getMessage());
                log.debug(e, e);
                Thread.sleep(delay.toMillis());
                delay = min(delay.multipliedBy(2), settings.maxDelay());
            }
        }
    }

    public SinkTestResult test(String principal) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString().replace("-", "");
        AuditEvent event = new AuditEvent(id, TEST_EVENT_ID, "auditForwarder", now, now, principal,
                "Test event sent from the Audit Forwarder administration page", null, null, null, null, null,
                Map.of(), sourceHost);
        long start = System.nanoTime();
        try {
            sink.send(List.of(event));
            recordSuccess(0);
            return new SinkTestResult(settings.name(), true, "Delivered", elapsedMillis(start));
        } catch (SinkException | RuntimeException e) {
            recordFailure(e);
            return new SinkTestResult(settings.name(), false, e.getMessage(), elapsedMillis(start));
        }
    }

    public SinkStatus status() {
        return new SinkStatus(settings.name(), settings.type(), sink.target(), sent.get(), failedAttempts.get(),
                dropped.get(), lastSuccessAt, lastError, lastErrorAt, failing);
    }

    @Override
    public void close() {
        try {
            sink.close();
        } catch (RuntimeException e) {
            log.warn("Audit forwarder sink '{}': error while closing", settings.name(), e);
        }
    }

    protected void recordSuccess(int count) {
        sent.addAndGet(count);
        lastSuccessAt = Instant.now();
        failing = false;
    }

    protected void recordFailure(Exception e) {
        failedAttempts.incrementAndGet();
        lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        lastErrorAt = Instant.now();
        failing = true;
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
