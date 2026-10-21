/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.sink.AuditSink;
import io.maretha.audit.forwarder.sink.SqsSink;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

/** Against a real SQS API (LocalStack); skipped without Docker or a LocalStack auth token. */
public class TestSqsSink {

    static final String AUTH_TOKEN_ENV = "LOCALSTACK_AUTH_TOKEN";

    static LocalStackContainer localStack;

    static SqsClient sqs;

    @BeforeClass
    public static void startLocalStack() {
        String token = System.getenv(AUTH_TOKEN_ENV);
        Assume.assumeTrue(AUTH_TOKEN_ENV + " is not set: current LocalStack images require it",
                token != null && !token.isBlank());
        Assume.assumeTrue("Docker is not available", DockerClientFactory.instance().isDockerAvailable());
        String image = System.getProperty("localstack.image", "localstack/localstack:2026.09.0");
        localStack = new LocalStackContainer(DockerImageName.parse(image)).withServices("sqs")
                                                                         .withEnv(AUTH_TOKEN_ENV, token);
        localStack.start();
        sqs = SqsClient.builder()
                       .endpointOverride(localStack.getEndpoint())
                       .region(Region.of(localStack.getRegion()))
                       .credentialsProvider(StaticCredentialsProvider.create(
                               AwsBasicCredentials.create(localStack.getAccessKey(), localStack.getSecretKey())))
                       .build();
    }

    @AfterClass
    public static void stopLocalStack() {
        if (sqs != null) {
            sqs.close();
        }
        if (localStack != null) {
            localStack.stop();
        }
    }

    static AuditSink sink(String queueUrl) {
        return new SqsSink(Fixtures.settings("queue", "queue.url", queueUrl, "region", localStack.getRegion(),
                "endpoint", localStack.getEndpoint().toString(), "access.key.id", localStack.getAccessKey(),
                "secret.access.key", localStack.getSecretKey()));
    }

    static List<Message> receiveAll(String queueUrl, int expected) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 10 && messages.size() < expected; i++) {
            messages.addAll(sqs.receiveMessage(b -> b.queueUrl(queueUrl)
                                                     .maxNumberOfMessages(10)
                                                     .waitTimeSeconds(1)
                                                     .messageAttributeNames("All")
                                                     .messageSystemAttributeNames(
                                                             MessageSystemAttributeName.MESSAGE_GROUP_ID))
                               .messages());
        }
        return messages;
    }

    @Test
    public void sendsOneMessagePerEventInBatchesOfTen() throws Exception {
        String queueUrl = sqs.createQueue(b -> b.queueName("audit-standard")).queueUrl();
        List<AuditEvent> events = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            events.add(Fixtures.event("event" + i));
        }
        try (AuditSink sink = sink(queueUrl)) {
            sink.send(events);
        }
        List<Message> messages = receiveAll(queueUrl, 12);
        assertEquals(12, messages.size());
        Set<String> eventIds = new TreeSet<>();
        for (Message message : messages) {
            String eventId = EventJson.MAPPER.readTree(message.body()).get("eventId").asText();
            eventIds.add(eventId);
            assertEquals(eventId, message.messageAttributes().get("eventId").stringValue());
            assertEquals("eventDocumentCategory", message.messageAttributes().get("category").stringValue());
        }
        assertEquals(12, eventIds.size());
    }

    @Test
    public void fifoQueueDeduplicatesOnTheEventId() {
        String queueUrl = sqs.createQueue(b -> b.queueName("audit.fifo")
                                                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")))
                             .queueUrl();
        try (AuditSink sink = sink(queueUrl)) {
            sink.send(List.of(Fixtures.event("documentModified")));
            // a redelivery after a failure carries the same event id
            sink.send(List.of(Fixtures.event("documentModified")));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        List<Message> messages = receiveAll(queueUrl, 2);
        assertEquals(1, messages.size());
        assertEquals("doc-uuid-1", messages.get(0).attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID));
    }
}
