/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.transport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.sink.SinkException;

/**
 * HTTP POST over the JDK client, with the sink's TLS settings. Redirects are not followed, so credentials never leave
 * for another host. Error messages name the host and path only: query strings may carry signatures.
 */
public class HttpTransport {

    private static final Logger log = LogManager.getLogger(HttpTransport.class);

    protected final HttpClient client;

    protected final Duration requestTimeout;

    public HttpTransport(SinkSettings settings) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                                               .connectTimeout(settings.getMillis("connect.timeout.ms", 10000))
                                               .followRedirects(HttpClient.Redirect.NEVER);
        SSLContext sslContext = TlsSupport.sslContext(settings);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        client = builder.build();
        requestTimeout = settings.getMillis("request.timeout.ms", 30000);
    }

    /** Logs a warning when credentials would travel in clear text. */
    public static void warnIfPlainHttp(SinkSettings settings, URI uri) {
        if ("http".equalsIgnoreCase(uri.getScheme())) {
            log.warn("Audit forwarder sink '{}': {} uses plain http, credentials and audit data are not encrypted",
                    settings.name(), uri.getHost());
        }
    }

    /** Fails at startup, not at the first send, when a header name or value is rejected by the HTTP client. */
    public static void validateHeaders(SinkSettings settings, URI uri, Map<String, String> headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        headers.forEach((name, value) -> {
            try {
                builder.header(name, value);
            } catch (IllegalArgumentException e) {
                throw settings.invalid("header '" + name + "' is not allowed: " + e.getMessage());
            }
        });
    }

    public HttpResponse<String> post(URI uri, Map<String, String> headers, byte[] body) throws SinkException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                                                 .timeout(requestTimeout)
                                                 .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SinkException("cannot reach " + describe(uri) + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SinkException("interrupted while sending to " + describe(uri), e);
        }
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            String detail = StringUtils.abbreviate(StringUtils.normalizeSpace(response.body()), 300);
            String retryAfter = response.headers().firstValue("Retry-After").map(v -> ", Retry-After " + v).orElse("");
            throw new HttpStatusException(status,
                    "HTTP " + status + " from " + describe(uri) + retryAfter + (detail.isEmpty() ? "" : ": " + detail));
        }
        return response;
    }

    public static String describe(URI uri) {
        String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
        return uri.getScheme() + "://" + uri.getHost() + port + StringUtils.defaultString(uri.getRawPath());
    }

    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 64);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
