/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.sink.AuditSink;
import io.maretha.audit.forwarder.sink.AuditSinkFactory;
import io.maretha.audit.forwarder.sink.SinkException;

/** Test sink type {@code memory}; {@code fail=true} makes every send fail. */
public class MemorySink implements AuditSink {

    public static class Factory implements AuditSinkFactory {
        @Override
        public AuditSink create(SinkSettings settings) {
            return new MemorySink(settings.name(), settings.getBoolean("fail", false));
        }
    }

    static final Map<String, List<AuditEvent>> RECEIVED = new ConcurrentHashMap<>();

    protected final String name;

    protected final boolean fail;

    MemorySink(String name, boolean fail) {
        this.name = name;
        this.fail = fail;
    }

    @Override
    public String target() {
        return "memory:" + name;
    }

    @Override
    public void send(List<AuditEvent> events) throws SinkException {
        if (fail) {
            throw new SinkException("simulated outage");
        }
        received(name).addAll(events);
    }

    static List<AuditEvent> received(String sink) {
        return RECEIVED.computeIfAbsent(sink, k -> new CopyOnWriteArrayList<>());
    }

    static void clear() {
        RECEIVED.clear();
    }

    static AuditEvent await(String sink, Predicate<AuditEvent> match, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (AuditEvent event : received(sink)) {
                if (match.test(event)) {
                    return event;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No matching event reached sink '" + sink + "' within " + timeout + ", received: "
                + received(sink).stream().map(AuditEvent::eventId).toList());
    }
}
