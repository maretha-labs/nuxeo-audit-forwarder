/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.operation;

import java.util.LinkedHashMap;
import java.util.Map;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;

import io.maretha.audit.forwarder.AuditForwarderException;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.settings.ForwarderSettings;
import io.maretha.audit.forwarder.sink.SinkStatus;
import io.maretha.audit.forwarder.sink.SinkTestResult;

/** Shared by the operations: administrator check and JSON responses. Nothing here exposes a credential. */
final class AdminJson {

    private AdminJson() {
    }

    static void requireAdministrator(CoreSession session) {
        NuxeoPrincipal principal = session.getPrincipal();
        if (principal == null || !principal.isAdministrator()) {
            throw new AuditForwarderException("Only administrators can manage the audit forwarder", 403);
        }
    }

    static Blob json(Object value) {
        return Blobs.createJSONBlob(EventJson.toJson(value));
    }

    static Map<String, Object> settings(ForwarderSettings settings, boolean enabled) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("events", settings.events());
        map.put("fromDocument", settings.fromDocument());
        map.put("updatedAt", EventJson.iso(settings.updatedAt()));
        map.put("updatedBy", settings.updatedBy());
        return map;
    }

    static Map<String, Object> status(SinkStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", status.name());
        map.put("type", status.type());
        map.put("target", status.target());
        map.put("sent", status.sent());
        map.put("failedAttempts", status.failedAttempts());
        map.put("dropped", status.dropped());
        map.put("lastSuccessAt", EventJson.iso(status.lastSuccessAt()));
        map.put("lastError", status.lastError());
        map.put("lastErrorAt", EventJson.iso(status.lastErrorAt()));
        map.put("failing", status.failing());
        return map;
    }

    static Map<String, Object> testResult(SinkTestResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sink", result.sink());
        map.put("ok", result.ok());
        map.put("message", result.message());
        map.put("durationMs", result.durationMs());
        return map;
    }
}
