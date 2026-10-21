/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.runtime.model.Descriptor;

/**
 * Forwarder configuration contributed by {@code OSGI-INF/audit-forwarder-config.xml}. Values are expanded from
 * nuxeo.conf when the contribution is deployed; this class holds no defaults of its own.
 */
@XObject("forwarder")
public class AuditForwarderDescriptor implements Descriptor {

    @XNode("enabled")
    protected boolean enabled;

    @XNode("sinks")
    protected String sinks;

    @XNode("defaultEvents")
    protected String defaultEvents;

    @XNode("excludePrincipals")
    protected String excludePrincipals;

    @XNode("sourceHost")
    protected String sourceHost;

    @XNode("settingsCacheSeconds")
    protected long settingsCacheSeconds;

    @XNode("sinkPropertyPrefix")
    protected String sinkPropertyPrefix;

    @Override
    public String getId() {
        return UNIQUE_DESCRIPTOR_ID;
    }
}
