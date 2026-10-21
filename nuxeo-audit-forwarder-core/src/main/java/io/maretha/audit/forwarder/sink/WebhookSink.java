/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.format.Product;
import io.maretha.audit.forwarder.transport.HttpStatusException;
import io.maretha.audit.forwarder.transport.HttpTransport;
import io.maretha.audit.forwarder.transport.OAuth2ClientCredentials;

/**
 * Any HTTPS endpoint. Authentication is mandatory to declare ({@code auth.type=none} must be explicit): bearer token, basic,
 * a custom header, or an OAuth 2.0 client credentials token. An optional HMAC-SHA256 signature lets the receiver check
 * integrity and freshness: it signs {@code <timestamp>.<body>} and the timestamp travels in its own header.
 */
public class WebhookSink implements AuditSink {

    public static class Factory implements AuditSinkFactory {
        @Override
        public AuditSink create(SinkSettings settings) {
            return new WebhookSink(settings);
        }
    }

    public static final String TIMESTAMP_HEADER = "X-Audit-Forwarder-Timestamp";

    protected final URI uri;

    protected final String format;

    protected final Map<String, String> staticHeaders = new LinkedHashMap<>();

    protected final OAuth2ClientCredentials oauth;

    protected final byte[] hmacSecret;

    protected final String hmacHeader;

    protected final HttpTransport http;

    public WebhookSink(SinkSettings settings) {
        uri = settings.requireHttpUri("url");
        format = settings.choice("format", "json", "json", "ndjson");
        http = new HttpTransport(settings);
        String auth = settings.choice("auth.type", null, "none", "bearer", "basic", "header", "oauth2");
        OAuth2ClientCredentials oauth2 = null;
        switch (auth) {
            case "bearer" -> staticHeaders.put("Authorization", "Bearer " + settings.require("auth.token"));
            case "basic" -> {
                String credentials = settings.require("auth.username") + ":" + settings.require("auth.password");
                staticHeaders.put("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8)));
            }
            case "header" -> staticHeaders.put(settings.require("auth.header.name"),
                    settings.require("auth.header.value"));
            case "oauth2" -> oauth2 = new OAuth2ClientCredentials(http, settings.requireHttpUri("oauth2.token.url"),
                    settings.require("oauth2.client.id"), settings.require("oauth2.client.secret"),
                    settings.get("oauth2.scope"),
                    "basic".equals(settings.choice("oauth2.client.auth", "post", "post", "basic")));
            default -> {
                // auth.type=none, explicitly chosen
            }
        }
        oauth = oauth2;
        settings.withPrefix("headers.").forEach(staticHeaders::putIfAbsent);
        String secret = settings.get("hmac.secret");
        hmacSecret = secret == null ? null : secret.getBytes(UTF_8);
        hmacHeader = settings.get("hmac.header", "X-Audit-Forwarder-Signature");
        HttpTransport.validateHeaders(settings, uri, staticHeaders);
        HttpTransport.warnIfPlainHttp(settings, uri);
    }

    @Override
    public String target() {
        return HttpTransport.describe(uri);
    }

    @Override
    public void send(List<AuditEvent> events) throws SinkException {
        String body;
        Map<String, String> headers = new LinkedHashMap<>(staticHeaders);
        headers.put("User-Agent", "nuxeo-audit-forwarder/" + Product.VERSION);
        if ("ndjson".equals(format)) {
            body = events.stream().map(e -> EventJson.toJson(EventJson.toMap(e)) + "\n").collect(Collectors.joining());
            headers.put("Content-Type", "application/x-ndjson");
        } else {
            body = EventJson.toJson(events.stream().map(EventJson::toMap).toList());
            headers.put("Content-Type", "application/json");
        }
        if (hmacSecret != null) {
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            headers.put(TIMESTAMP_HEADER, timestamp);
            headers.put(hmacHeader, "sha256=" + sign(timestamp + "." + body));
        }
        if (oauth != null) {
            headers.put("Authorization", "Bearer " + oauth.token());
        }
        try {
            http.post(uri, headers, body.getBytes(UTF_8));
        } catch (HttpStatusException e) {
            if (e.status() == 401 && oauth != null) {
                oauth.invalidate();
            }
            throw e;
        }
    }

    protected String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
