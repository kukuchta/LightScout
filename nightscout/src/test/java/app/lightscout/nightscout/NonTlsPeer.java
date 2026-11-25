package app.lightscout.nightscout;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * BUG-01: a deterministic non-TLS peer on the IPv4/IPv6 loopback address (single address, so OkHttp tries exactly
 * one route). For every connection it waits for the client's first TLS bytes (the ClientHello record header),
 * answers with a plain-text HTTP status line, half-closes its output and drains the input. The JDK TLS client
 * therefore always reads a non-TLS record and fails the handshake with an {@code SSLException} — never a
 * connection reset, refused connect or read timeout. Localhost only (CP-00 §8).
 */
final class NonTlsPeer implements Closeable {

    private static final byte[] PLAIN_REPLY =
            "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII);

    private final ServerSocket serverSocket;
    private final Thread acceptor;

    /**
     * Starts the peer on an ephemeral loopback port.
     *
     * @throws IOException if the port cannot be bound
     */
    NonTlsPeer() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "non-tls-peer");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /**
     * Returns an https URL that addresses this peer by its literal loopback IP.
     *
     * @return {@code https://<loopback literal>:<port>/}
     */
    String httpsUrl() {
        String host = serverSocket.getInetAddress().getHostAddress();
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return "https://" + host + ":" + serverSocket.getLocalPort() + "/";
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try (Socket s = serverSocket.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                byte[] header = new byte[5];
                int read = 0;
                while (read < header.length) {
                    int n = in.read(header, read, header.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                out.write(PLAIN_REPLY);
                out.flush();
                s.shutdownOutput();
                byte[] drain = new byte[4096];
                s.setSoTimeout(5000);
                while (in.read(drain) >= 0) {
                    // drain until the client closes, so the close is orderly (FIN, no RST)
                }
            } catch (IOException e) {
                // next connection (or closed)
            }
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
