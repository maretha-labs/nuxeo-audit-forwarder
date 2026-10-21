/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import java.util.LinkedHashMap;
import java.util.Map;

import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.management.api.Probe;
import org.nuxeo.runtime.management.api.ProbeStatus;

import io.maretha.audit.forwarder.sink.SinkStatus;

/** Fails while the latest delivery attempt of any sink failed. Succeeds when the forwarder is disabled. */
public class AuditForwarderProbe implements Probe {

    @Override
    public ProbeStatus run() {
        AuditForwarderService service = Framework.getService(AuditForwarderService.class);
        if (service == null) {
            return ProbeStatus.newFailure("Audit forwarder service not available");
        }
        if (!service.isEnabled()) {
            return ProbeStatus.newSuccess("Audit forwarder disabled");
        }
        Map<String, String> infos = new LinkedHashMap<>();
        boolean failing = false;
        for (SinkStatus status : service.getSinkStatuses()) {
            if (status.failing()) {
                failing = true;
                infos.put(status.name(), "failing since " + status.lastErrorAt() + ": " + status.lastError());
            } else {
                infos.put(status.name(), "ok, " + status.sent() + " sent, " + status.dropped() + " dropped");
            }
        }
        return failing ? ProbeStatus.newFailure(infos) : ProbeStatus.newSuccess(infos);
    }
}
