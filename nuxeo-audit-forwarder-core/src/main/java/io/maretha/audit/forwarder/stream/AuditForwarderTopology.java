/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.nuxeo.ecm.platform.audit.listener.StreamAuditEventListener.STREAM_NAME;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.platform.audit.api.LogEntry;
import org.nuxeo.ecm.platform.audit.impl.LogEntryImpl;
import org.nuxeo.lib.stream.computation.AbstractBatchComputation;
import org.nuxeo.lib.stream.computation.ComputationContext;
import org.nuxeo.lib.stream.computation.Record;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamProcessorTopology;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.AuditForwarderService;

/**
 * One computation per sink, each reading the audit stream ({@code audit/audit}) in its own consumer group named after
 * the computation. Offsets are independent: a sink that is down holds back only itself, and resumes where it stopped.
 * Nuxeo's own audit writer is unaffected.
 */
public class AuditForwarderTopology implements StreamProcessorTopology {

    public static final String COMPUTATION_PREFIX = "audit/forwarder-";

    @Override
    public Topology getTopology(Map<String, String> options) {
        AuditForwarderService service = Framework.getService(AuditForwarderService.class);
        Topology.Builder builder = Topology.builder();
        for (String sink : service.getSinkNames()) {
            builder.addComputation(() -> new ForwardComputation(COMPUTATION_PREFIX + sink, sink),
                    List.of("i1:" + STREAM_NAME));
        }
        return builder.build();
    }

    public static class ForwardComputation extends AbstractBatchComputation {

        private static final Logger log = LogManager.getLogger(ForwardComputation.class);

        private static final ObjectMapper MAPPER = new ObjectMapper();

        protected final String sink;

        public ForwardComputation(String name, String sink) {
            super(name, 1, 0);
            this.sink = sink;
        }

        @Override
        public void batchProcess(ComputationContext context, String inputStreamName, List<Record> records) {
            AuditForwarderService service = Framework.getService(AuditForwarderService.class);
            String sourceHost = service.getSourceHost();
            List<AuditEvent> events = new ArrayList<>(records.size());
            for (Record record : records) {
                try {
                    events.add(AuditEvent.from(decode(record.getData()), sourceHost));
                } catch (NuxeoException e) {
                    log.error("Audit forwarder sink '{}': discarding unreadable record {}", sink, record.getKey(), e);
                }
            }
            try {
                service.forward(sink, events);
            } catch (InterruptedException e) {
                // the batch is not acknowledged and will be read again after restart
                Thread.currentThread().interrupt();
                throw new NuxeoException("Interrupted while forwarding to sink '" + sink + "'", e);
            }
        }

        @Override
        public void batchFailure(ComputationContext context, String inputStreamName, List<Record> records) {
            // logged by AbstractBatchComputation; the stream policy retries the batch
        }

        // LTS 2023 has no LogEntry JSON reader: decode the way its StreamAuditWriter does
        protected LogEntry decode(byte[] data) {
            String json = new String(data, UTF_8);
            try {
                return MAPPER.readValue(json, LogEntryImpl.class);
            } catch (IOException e) {
                throw new NuxeoException("Invalid audit log entry JSON", e);
            }
        }
    }
}
