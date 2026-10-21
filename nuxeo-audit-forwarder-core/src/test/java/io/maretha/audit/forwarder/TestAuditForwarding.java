/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

import com.fasterxml.jackson.databind.JsonNode;

import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.operation.GetSettings;
import io.maretha.audit.forwarder.operation.ListSinks;
import io.maretha.audit.forwarder.operation.TestSink;
import io.maretha.audit.forwarder.sink.SinkRuntime;
import io.maretha.audit.forwarder.sink.SinkStatus;

/**
 * End to end inside Nuxeo: document events go through the audit stream to two sinks, one of which is down.
 */
@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("org.nuxeo.ecm.platform.audit")
@Deploy("io.maretha.audit.forwarder.core:OSGI-INF/audit-forwarder-service.xml")
@Deploy("io.maretha.audit.forwarder.core:OSGI-INF/audit-forwarder-config.xml")
@Deploy("io.maretha.audit.forwarder.core:OSGI-INF/audit-forwarder-sinks.xml")
@Deploy("io.maretha.audit.forwarder.core:OSGI-INF/audit-forwarder-types.xml")
@Deploy("io.maretha.audit.forwarder.core:OSGI-INF/audit-forwarder-stream.xml")
@Deploy("io.maretha.audit.forwarder.core:OSGI-INF/audit-forwarder-operations.xml")
@Deploy("io.maretha.audit.forwarder.core.tests:OSGI-INF/test-memory-sink.xml")
@WithFrameworkProperty(name = "nuxeo.audit.backend.default.factory", value = "org.nuxeo.audit.mem.MemAuditBackendFactory")
@WithFrameworkProperty(name = "maretha.audit.forwarder.enabled", value = "true")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sinks", value = "good,down")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sink.good.type", value = "memory")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sink.down.type", value = "memory")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sink.down.fail", value = "true")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sink.down.continueOnFailure", value = "true")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sink.down.retry.maxAttempts", value = "100")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sink.down.retry.initialDelay.ms", value = "20")
@WithFrameworkProperty(name = "maretha.audit.forwarder.sink.down.retry.maxDelay.ms", value = "20")
@WithFrameworkProperty(name = "maretha.audit.forwarder.settings.cache.seconds", value = "0")
@WithFrameworkProperty(name = "maretha.audit.forwarder.source.host", value = "test-node")
public class TestAuditForwarding {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Inject
    protected CoreSession session;

    @Inject
    protected AuditForwarderService service;

    @Inject
    protected AutomationService automation;

    @Inject
    protected TransactionalFeature txFeature;

    @Before
    public void reset() {
        MemorySink.clear();
        service.saveSettings(session, List.of("*"));
    }

    protected DocumentModel createFile(String name) {
        DocumentModel doc = session.createDocument(session.createDocumentModel("/", name, "File"));
        txFeature.nextTransaction();
        return doc;
    }

    protected SinkStatus status(String sink) {
        return service.getSinkStatuses().stream().filter(s -> s.name().equals(sink)).findFirst().orElseThrow();
    }

    @Test
    public void forwardsDocumentEventsAndIsolatesAFailingSink() throws Exception {
        assertEquals(List.of("good", "down"), service.getSinkNames());
        DocumentModel doc = createFile("file1");
        AuditEvent event = MemorySink.await("good",
                e -> "documentCreated".equals(e.eventId()) && doc.getId().equals(e.docUUID()), TIMEOUT);
        assertEquals("Administrator", event.principal());
        assertEquals("/file1", event.docPath());
        assertEquals("File", event.docType());
        assertEquals("test-node", event.sourceHost());
        assertEquals(32, event.id().length());
        // the other sink is retrying the same batch meanwhile, in its own consumer group
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (status("down").failedAttempts() == 0 && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertTrue(status("down").failing());
        assertTrue(MemorySink.received("down").isEmpty());
        assertFalse(status("good").failing());
    }

    @Test
    public void appliesTheAdministratorEventSelection() throws Exception {
        service.saveSettings(session, List.of("documentModified"));
        DocumentModel doc = createFile("file2");
        doc.setPropertyValue("dc:title", "changed");
        session.saveDocument(doc);
        txFeature.nextTransaction();
        MemorySink.await("good", e -> "documentModified".equals(e.eventId()) && doc.getId().equals(e.docUUID()),
                TIMEOUT);
        assertTrue(MemorySink.received("good")
                             .stream()
                             .noneMatch(e -> "documentCreated".equals(e.eventId())
                                     && doc.getId().equals(e.docUUID())));
    }

    @Test
    public void operationsAreForAdministratorsOnly() throws Exception {
        CoreSession jdoe = CoreInstance.getCoreSession(session.getRepositoryName(), "jdoe");
        assertThrows(Exception.class, () -> automation.run(new OperationContext(jdoe), ListSinks.ID));
        assertThrows(Exception.class, () -> automation.run(new OperationContext(jdoe), GetSettings.ID));

        Blob blob = (Blob) automation.run(new OperationContext(session), ListSinks.ID);
        JsonNode sinks = EventJson.MAPPER.readTree(blob.getString());
        assertEquals(2, sinks.size());
        assertEquals("good", sinks.get(0).get("name").asText());
        assertEquals("memory:good", sinks.get(0).get("target").asText());

        JsonNode settings = EventJson.MAPPER.readTree(((Blob) automation.run(new OperationContext(session),
                GetSettings.ID)).getString());
        assertTrue(settings.get("enabled").asBoolean());
        assertEquals("*", settings.get("events").get(0).asText());
    }

    @Test
    public void testOperationSendsASyntheticEvent() throws Exception {
        OperationContext ctx = new OperationContext(session);
        JsonNode result = EventJson.MAPPER.readTree(
                ((Blob) automation.run(ctx, TestSink.ID, java.util.Map.of("sink", "good"))).getString());
        assertTrue(result.get("ok").asBoolean());
        AuditEvent event = MemorySink.await("good", e -> SinkRuntime.TEST_EVENT_ID.equals(e.eventId()), TIMEOUT);
        assertEquals("Administrator", event.principal());

        JsonNode failed = EventJson.MAPPER.readTree(
                ((Blob) automation.run(ctx, TestSink.ID, java.util.Map.of("sink", "down"))).getString());
        assertFalse(failed.get("ok").asBoolean());
        assertEquals("simulated outage", failed.get("message").asText());
    }
}
