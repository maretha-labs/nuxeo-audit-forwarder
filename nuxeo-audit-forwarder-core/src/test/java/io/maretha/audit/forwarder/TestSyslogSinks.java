/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

import org.junit.BeforeClass;
import org.junit.Test;

import io.maretha.audit.forwarder.sink.AuditSink;
import io.maretha.audit.forwarder.sink.QRadarSink;
import io.maretha.audit.forwarder.sink.SinkException;
import io.maretha.audit.forwarder.sink.SyslogSink;

public class TestSyslogSinks {

    static final String PASSWORD = "changeit";

    static Path keystore;

    static Path truststore;

    static final List<AuditEvent> EVENTS = List.of(Fixtures.event("documentCreated"), Fixtures.event("loginFailed"));

    /** A throwaway self-signed certificate for localhost, generated with the JDK's keytool. */
    @BeforeClass
    public static void createCertificates() throws Exception {
        Path dir = Files.createTempDirectory("audit-forwarder-tls");
        keystore = dir.resolve("key.p12");
        truststore = dir.resolve("trust.p12");
        Path cert = dir.resolve("cert.pem");
        keytool("-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12", "-keystore",
                keystore.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD);
        keytool("-exportcert", "-rfc", "-alias", "test", "-keystore", keystore.toString(), "-storepass", PASSWORD,
                "-file", cert.toString());
        keytool("-importcert", "-noprompt", "-alias", "test", "-file", cert.toString(), "-storetype", "PKCS12",
                "-keystore", truststore.toString(), "-storepass", PASSWORD);
    }

    static void keytool(String... args) throws Exception {
        String keytool = System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool";
        List<String> command = new java.util.ArrayList<>(List.of(keytool));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), UTF_8);
        assertEquals(output, 0, process.waitFor());
    }

    static SSLContext serverContext(boolean withTrust) throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            keys.load(in, PASSWORD.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD.toCharArray());
        TrustManagerFactory tmf = null;
        if (withTrust) {
            KeyStore trust = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(truststore)) {
                trust.load(in, PASSWORD.toCharArray());
            }
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
        return context;
    }

    /** Accepts one connection and decodes frames, octet-counted or newline-terminated. */
    static BlockingQueue<String> serve(ServerSocket server, boolean octetCounting) {
        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept(); DataInputStream in = new DataInputStream(socket.getInputStream())) {
                while (true) {
                    if (octetCounting) {
                        StringBuilder length = new StringBuilder();
                        int c;
                        while ((c = in.read()) != ' ') {
                            if (c < 0) {
                                return;
                            }
                            length.append((char) c);
                        }
                        byte[] frame = new byte[Integer.parseInt(length.toString())];
                        in.readFully(frame);
                        received.add(new String(frame, UTF_8));
                    } else {
                        ByteArrayOutputStream line = new ByteArrayOutputStream();
                        int c;
                        while ((c = in.read()) != '\n') {
                            if (c < 0) {
                                return;
                            }
                            line.write(c);
                        }
                        received.add(line.toString(UTF_8));
                    }
                }
            } catch (IOException e) {
                // test server closed
            }
        });
        thread.setDaemon(true);
        thread.start();
        return received;
    }

    @Test
    public void tcpOctetCountingWithJsonBody() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> received = serve(server, true);
            AuditSink sink = new SyslogSink.Factory().create(Fixtures.settings("sys", "host", "127.0.0.1", "port",
                    String.valueOf(server.getLocalPort()), "protocol", "tcp"));
            sink.send(EVENTS);
            String first = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first, first.startsWith("<110>1 2026-10-15T08:30:00.123Z nuxeo-node-1 nuxeo-audit - "
                    + "documentCreated - {\"id\":\"0123456789abcdef0123456789abcdef\""));
            String second = received.poll(5, TimeUnit.SECONDS);
            assertTrue(second, second.startsWith("<108>1 "));
            sink.close();
        }
    }

    @Test
    public void tlsVerifiesServerAndPresentsClientCertificate() throws Exception {
        SSLContext context = serverContext(true);
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                                                             .createServerSocket(0, 1,
                                                                     InetAddress.getLoopbackAddress())) {
            server.setNeedClientAuth(true);
            BlockingQueue<String> received = serve(server, true);
            AuditSink sink = new SyslogSink.Factory().create(Fixtures.settings("sys", "host", "localhost", "port",
                    String.valueOf(server.getLocalPort()), "format", "cef", "tls.truststore.path", truststore.toString(),
                    "tls.truststore.password", PASSWORD, "tls.keystore.path", keystore.toString(),
                    "tls.keystore.password", PASSWORD));
            sink.send(EVENTS);
            String first = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first, first.contains(" documentCreated - CEF:0|Maretha|"));
            sink.close();
        }
    }

    @Test
    public void tlsRejectsAnUntrustedServer() throws Exception {
        SSLContext context = serverContext(false);
        try (SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory()
                                                             .createServerSocket(0, 1,
                                                                     InetAddress.getLoopbackAddress())) {
            serve(server, true);
            // no truststore: the JVM default CAs do not trust the self-signed certificate
            AuditSink sink = new SyslogSink.Factory().create(Fixtures.settings("sys", "host", "localhost", "port",
                    String.valueOf(server.getLocalPort())));
            SinkException e = assertThrows(SinkException.class, () -> sink.send(EVENTS));
            assertTrue(e.getMessage(), e.getMessage().startsWith("cannot send to tls://localhost:"));
            sink.close();
        }
    }

    @Test
    public void udpOneDatagramPerEvent() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            AuditSink sink = new SyslogSink.Factory().create(Fixtures.settings("sys", "host", "127.0.0.1", "port",
                    String.valueOf(server.getLocalPort()), "protocol", "udp", "format", "leef", "facility", "4"));
            sink.send(EVENTS);
            DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
            server.receive(packet);
            String message = new String(packet.getData(), 0, packet.getLength(), UTF_8);
            assertTrue(message, message.startsWith("<38>1 "));
            assertTrue(message, message.contains(" - LEEF:2.0|Maretha|"));
            sink.close();
        }
    }

    @Test
    public void qradarSyslogDefaultsToLeefWithNewlineFraming() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> received = serve(server, false);
            AuditSink sink = new QRadarSink(Fixtures.settings("qradar", "host", "127.0.0.1", "port",
                    String.valueOf(server.getLocalPort()), "protocol", "tcp"));
            sink.send(EVENTS);
            String first = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertTrue(first, first.matches("<110>1 \\S+ nuxeo-node-1 nuxeo-audit - documentCreated - LEEF:2\\.0\\|.*"));
            assertNotNull(received.poll(5, TimeUnit.SECONDS));
            sink.close();
        }
    }

    @Test
    public void reconnectsAfterTheReceiverRestarts() throws Exception {
        int port;
        try (ServerSocket first = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = first.getLocalPort();
        }
        AuditSink sink = new SyslogSink.Factory().create(Fixtures.settings("sys", "host", "127.0.0.1", "port",
                String.valueOf(port), "protocol", "tcp"));
        assertThrows(SinkException.class, () -> sink.send(EVENTS));
        try (ServerSocket server = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> received = serve(server, true);
            sink.send(EVENTS);
            assertNotNull(received.poll(5, TimeUnit.SECONDS));
        }
        sink.close();
    }

    /** A receiver restart closes the connection; the next batch must go to a new one, not into the dead socket. */
    @Test
    public void reconnectsWhenTheReceiverClosedTheConnection() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            BlockingQueue<String> received = new LinkedBlockingQueue<>();
            Thread thread = new Thread(() -> {
                try {
                    for (int connection = 1; connection <= 2; connection++) {
                        try (Socket socket = server.accept();
                                DataInputStream in = new DataInputStream(socket.getInputStream())) {
                            for (int i = 0; i < EVENTS.size(); i++) {
                                StringBuilder length = new StringBuilder();
                                int c;
                                while ((c = in.read()) != ' ') {
                                    length.append((char) c);
                                }
                                byte[] frame = new byte[Integer.parseInt(length.toString())];
                                in.readFully(frame);
                                received.add(connection + ":" + new String(frame, UTF_8));
                            }
                        }
                    }
                } catch (IOException e) {
                    // test server closed
                }
            });
            thread.setDaemon(true);
            thread.start();
            AuditSink sink = new SyslogSink.Factory().create(Fixtures.settings("sys", "host", "127.0.0.1", "port",
                    String.valueOf(server.getLocalPort()), "protocol", "tcp"));
            sink.send(EVENTS);
            assertTrue(received.poll(5, TimeUnit.SECONDS).startsWith("1:"));
            assertTrue(received.poll(5, TimeUnit.SECONDS).startsWith("1:"));
            // the receiver has now closed the first connection
            Thread.sleep(200);
            sink.send(EVENTS);
            String next = received.poll(5, TimeUnit.SECONDS);
            assertNotNull("second batch lost", next);
            assertTrue(next, next.startsWith("2:"));
            assertNotNull(received.poll(5, TimeUnit.SECONDS));
            sink.close();
        }
    }
}
