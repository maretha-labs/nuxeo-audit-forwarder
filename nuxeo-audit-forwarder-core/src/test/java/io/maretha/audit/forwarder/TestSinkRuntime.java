/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import io.maretha.audit.forwarder.sink.AuditSink;
import io.maretha.audit.forwarder.sink.SinkException;
import io.maretha.audit.forwarder.sink.SinkRuntime;
import io.maretha.audit.forwarder.sink.SinkStatus;
import io.maretha.audit.forwarder.sink.SinkTestResult;

public class TestSinkRuntime {

    /** Fails the first {@code failures} sends, then records batches. */
    static class FlakySink implements AuditSink {

        final AtomicInteger remainingFailures;

        final List<List<AuditEvent>> batches = new ArrayList<>();

        int calls;

        FlakySink(int failures) {
            remainingFailures = new AtomicInteger(failures);
        }

        @Override
        public String target() {
            return "memory";
        }

        @Override
        public void send(List<AuditEvent> events) throws SinkException {
            calls++;
            if (remainingFailures.getAndDecrement() > 0) {
                throw new SinkException("receiver unavailable");
            }
            batches.add(List.copyOf(events));
        }
    }

    static List<AuditEvent> events(int count) {
        List<AuditEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(Fixtures.event("e" + i));
        }
        return events;
    }

    @Test
    public void retriesUntilDeliveredByDefault() throws Exception {
        FlakySink sink = new FlakySink(7);
        SinkRuntime runtime = new SinkRuntime(Fixtures.settings("s", "type", "memory", "retry.initialDelay.ms", "1",
                "retry.maxDelay.ms", "2"), sink, "host");
        runtime.deliver(events(3));
        assertEquals(8, sink.calls);
        assertEquals(1, sink.batches.size());
        SinkStatus status = runtime.status();
        assertEquals(3, status.sent());
        assertEquals(7, status.failedAttempts());
        assertEquals(0, status.dropped());
        assertFalse(status.failing());
        assertEquals("receiver unavailable", status.lastError());
    }

    @Test
    public void dropsAfterMaxAttemptsWhenContinuingOnFailure() throws Exception {
        FlakySink sink = new FlakySink(100);
        SinkRuntime runtime = new SinkRuntime(Fixtures.settings("s", "type", "memory", "continueOnFailure", "true",
                "retry.maxAttempts", "3", "retry.initialDelay.ms", "1"), sink, "host");
        runtime.deliver(events(4));
        assertEquals(3, sink.calls);
        SinkStatus status = runtime.status();
        assertEquals(4, status.dropped());
        assertEquals(0, status.sent());
        assertTrue(status.failing());
    }

    @Test
    public void splitsIntoBatches() throws Exception {
        FlakySink sink = new FlakySink(0);
        SinkRuntime runtime = new SinkRuntime(Fixtures.settings("s", "type", "memory", "batch.size", "2"), sink,
                "host");
        runtime.deliver(events(5));
        assertEquals(List.of(2, 2, 1), sink.batches.stream().map(List::size).toList());
    }

    @Test
    public void appliesThePerSinkEventFilter() {
        SinkRuntime runtime = new SinkRuntime(Fixtures.settings("s", "events", "loginFailed, logout"),
                new FlakySink(0), "host");
        assertTrue(runtime.accepts(Fixtures.event("loginFailed")));
        assertFalse(runtime.accepts(Fixtures.event("documentModified")));
        SinkRuntime all = new SinkRuntime(Fixtures.settings("s"), new FlakySink(0), "host");
        assertTrue(all.accepts(Fixtures.event("documentModified")));
    }

    @Test
    public void testSendReportsTheOutcome() {
        FlakySink sink = new FlakySink(1);
        SinkRuntime runtime = new SinkRuntime(Fixtures.settings("s"), sink, "host");
        SinkTestResult failed = runtime.test("Administrator");
        assertFalse(failed.ok());
        assertEquals("receiver unavailable", failed.message());
        SinkTestResult ok = runtime.test("Administrator");
        assertTrue(ok.ok());
        AuditEvent sent = sink.batches.get(0).get(0);
        assertEquals(SinkRuntime.TEST_EVENT_ID, sent.eventId());
        assertEquals("Administrator", sent.principal());
        assertEquals("host", sent.sourceHost());
        assertEquals(0, runtime.status().sent());
    }
}
