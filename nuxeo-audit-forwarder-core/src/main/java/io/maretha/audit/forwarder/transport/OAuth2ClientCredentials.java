/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.transport;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.sink.SinkException;

/**
 * OAuth 2.0 client credentials grant (RFC 6749 section 4.4). The access token is cached until one minute before it
 * expires, or until {@link #invalidate()} after the receiver rejects it.
 */
public class OAuth2ClientCredentials {

    protected final HttpTransport http;

    protected final URI tokenUri;

    protected final String clientId;

    protected final String clientSecret;

    protected final String scope;

    protected final boolean basicAuth;

    protected String accessToken;

    protected Instant refreshAt = Instant.MIN;

    /** @param basicAuth true to send the client credentials in an Authorization header instead of the form body */
    public OAuth2ClientCredentials(HttpTransport http, URI tokenUri, String clientId, String clientSecret,
            String scope, boolean basicAuth) {
        this.http = http;
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.basicAuth = basicAuth;
    }

    public synchronized String token() throws SinkException {
        if (accessToken != null && Instant.now().isBefore(refreshAt)) {
            return accessToken;
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        if (basicAuth) {
            String credentials = urlEncode(clientId) + ":" + urlEncode(clientSecret);
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(UTF_8)));
        } else {
            form.put("client_id", clientId);
            form.put("client_secret", clientSecret);
        }
        if (scope != null) {
            form.put("scope", scope);
        }
        String body = form.entrySet()
                          .stream()
                          .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                          .collect(Collectors.joining("&"));
        HttpResponse<String> response;
        try {
            response = http.post(tokenUri, headers, body.getBytes(UTF_8));
        } catch (HttpStatusException e) {
            throw new SinkException("token request rejected: " + e.getMessage(), e);
        }
        try {
            JsonNode json = EventJson.MAPPER.readTree(response.body());
            String token = json.path("access_token").asText(null);
            if (token == null || token.isEmpty()) {
                throw new SinkException("token endpoint " + HttpTransport.describe(tokenUri)
                        + " returned no access_token");
            }
            long expiresIn = json.path("expires_in").asLong(300);
            accessToken = token;
            refreshAt = Instant.now().plusSeconds(Math.max(0, expiresIn - 60));
            return token;
        } catch (java.io.IOException e) {
            throw new SinkException("token endpoint " + HttpTransport.describe(tokenUri) + " returned invalid JSON", e);
        }
    }

    public synchronized void invalidate() {
        accessToken = null;
        refreshAt = Instant.MIN;
    }

    static String urlEncode(String value) {
        return URLEncoder.encode(value, UTF_8);
    }
}
