/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/** Records every request and answers with a status and body chosen per path. */
public class StubHttpServer implements AutoCloseable {

    public record Request(String method, String path, String query, Headers headers, byte[] body) {

        public String header(String name) {
            return headers.getFirst(name);
        }

        public String text() {
            try {
                if ("gzip".equals(header("Content-Encoding"))) {
                    return new String(new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes(), UTF_8);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            return new String(body, UTF_8);
        }
    }

    public record Response(int status, String body) {
    }

    protected final HttpServer server;

    public final List<Request> requests = new CopyOnWriteArrayList<>();

    protected final Map<String, List<Response>> responses = new ConcurrentHashMap<>();

    public StubHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders(), body));
            Response response = next(path);
            byte[] out = response.body().getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), out.length == 0 ? -1 : out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    /** Queues responses for {@code path}; the last one repeats. */
    public void respond(String path, Response... queued) {
        responses.put(path, new CopyOnWriteArrayList<>(List.of(queued)));
    }

    protected Response next(String path) {
        List<Response> queued = responses.get(path);
        if (queued == null || queued.isEmpty()) {
            return new Response(200, "{}");
        }
        return queued.size() > 1 ? queued.remove(0) : queued.get(0);
    }

    public List<Request> requestsTo(String path) {
        return requests.stream().filter(r -> r.path().equals(path)).toList();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
