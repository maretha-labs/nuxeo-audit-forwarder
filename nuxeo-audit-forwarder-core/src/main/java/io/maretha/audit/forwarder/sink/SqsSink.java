/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.transport.AwsClients;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse;

/**
 * Amazon SQS, one message per event (the canonical JSON), sent with {@code SendMessageBatch} in groups of at most 10
 * messages and {@code max.batch.bytes}. Entries the queue rejects are resent up to three times before the batch fails.
 * On FIFO queues the deduplication id is the event id and the group id comes from {@code fifo.group.by}.
 */
public class SqsSink implements AuditSink {

    public static class Factory implements AuditSinkFactory {
        @Override
        public AuditSink create(SinkSettings settings) {
            return new SqsSink(settings);
        }
    }

    static final int MAX_ENTRIES = 10;

    static final int ENTRY_ATTEMPTS = 3;

    protected final String queueUrl;

    protected final boolean fifo;

    protected final String groupBy;

    protected final int maxBatchBytes;

    protected final AwsClients clients;

    public SqsSink(SinkSettings settings) {
        queueUrl = settings.require("queue.url");
        fifo = queueUrl.endsWith(".fifo");
        groupBy = settings.choice("fifo.group.by", "docuuid", "docuuid", "principal", "eventid", "repositoryid",
                "single");
        maxBatchBytes = settings.getInt("max.batch.bytes", 262_144);
        clients = new AwsClients(settings);
    }

    @Override
    public String target() {
        return queueUrl;
    }

    @Override
    public void send(List<AuditEvent> events) throws SinkException {
        List<SendMessageBatchRequestEntry> batch = new ArrayList<>();
        int batchBytes = 0;
        for (int i = 0; i < events.size(); i++) {
            SendMessageBatchRequestEntry entry = entry(String.valueOf(i), events.get(i));
            int size = entry.messageBody().getBytes(UTF_8).length + 256;
            if (!batch.isEmpty() && (batch.size() == MAX_ENTRIES || batchBytes + size > maxBatchBytes)) {
                sendBatch(batch);
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(entry);
            batchBytes += size;
        }
        if (!batch.isEmpty()) {
            sendBatch(batch);
        }
    }

    protected void sendBatch(List<SendMessageBatchRequestEntry> entries) throws SinkException {
        List<SendMessageBatchRequestEntry> pending = entries;
        for (int attempt = 1;; attempt++) {
            SendMessageBatchResponse response;
            try {
                response = clients.sqs()
                                  .sendMessageBatch(SendMessageBatchRequest.builder()
                                                                           .queueUrl(queueUrl)
                                                                           .entries(pending)
                                                                           .build());
            } catch (SdkException e) {
                throw new SinkException("SQS SendMessageBatch to " + queueUrl + " failed: " + e.getMessage(), e);
            }
            if (!response.hasFailed() || response.failed().isEmpty()) {
                return;
            }
            Map<String, BatchResultErrorEntry> failed = response.failed()
                                                                .stream()
                                                                .collect(Collectors.toMap(BatchResultErrorEntry::id,
                                                                        f -> f));
            if (attempt >= ENTRY_ATTEMPTS) {
                BatchResultErrorEntry first = failed.values().iterator().next();
                throw new SinkException("SQS rejected " + failed.size() + " of " + pending.size() + " messages: "
                        + first.code() + " " + first.message());
            }
            pending = pending.stream().filter(e -> failed.containsKey(e.id())).toList();
        }
    }

    protected SendMessageBatchRequestEntry entry(String id, AuditEvent event) {
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attribute(attributes, "eventId", event.eventId());
        attribute(attributes, "category", event.category());
        attribute(attributes, "repositoryId", event.repositoryId());
        var builder = SendMessageBatchRequestEntry.builder()
                                                  .id(id)
                                                  .messageBody(EventJson.toJson(EventJson.toMap(event)))
                                                  .messageAttributes(attributes);
        if (fifo) {
            builder.messageGroupId(groupId(event)).messageDeduplicationId(event.id());
        }
        return builder.build();
    }

    protected String groupId(AuditEvent event) {
        String value = switch (groupBy) {
            case "principal" -> event.principal();
            case "eventid" -> event.eventId();
            case "repositoryid" -> event.repositoryId();
            case "single" -> null;
            default -> event.docUUID();
        };
        return value == null || value.isEmpty() ? "nuxeo-audit" : value;
    }

    protected static void attribute(Map<String, MessageAttributeValue> attributes, String name, String value) {
        if (value != null && !value.isEmpty()) {
            attributes.put(name, MessageAttributeValue.builder().dataType("String").stringValue(value).build());
        }
    }

    @Override
    public void close() {
        clients.close();
    }
}
