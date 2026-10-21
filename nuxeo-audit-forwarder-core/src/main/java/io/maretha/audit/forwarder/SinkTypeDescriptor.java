/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.runtime.model.Descriptor;

import io.maretha.audit.forwarder.sink.AuditSinkFactory;

/** Maps a sink {@code type} value from nuxeo.conf to the factory that builds it. */
@XObject("sinkType")
public class SinkTypeDescriptor implements Descriptor {

    @XNode("@name")
    protected String name;

    @XNode("@factory")
    protected Class<? extends AuditSinkFactory> factory;

    @Override
    public String getId() {
        return name;
    }

    public AuditSinkFactory newFactory() {
        try {
            return factory.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AuditForwarderException("Cannot instantiate sink factory for type '" + name + "'", e);
        }
    }
}
