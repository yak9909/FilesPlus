package app.filesplus.extension.smb;

import com.hierynomus.mssmb2.SMB2Dialect;
import com.hierynomus.security.SecurityProvider;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.NtlmAuthenticator;
import com.hierynomus.smbj.connection.Connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

/** Creates smbj clients configured for Android: SMB2/3 only, NTLM only, bundled BouncyCastle. */
public final class SmbClientFactory {
    public static final int DEFAULT_PORT = 445;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final long READ_TIMEOUT_SEC = 30;
    // Lightweight BouncyCastle API (no JCA provider lookup), so it cannot clash with Android's own "BC".
    private static final SecurityProvider SECURITY = new BCSecurityProvider();

    // smbj opens the direct TCP socket on the connection caller's thread.
    private static final ThreadLocal<SmbDiagnostics.Call> CONNECT_TIMING = new ThreadLocal<>();

    private SmbClientFactory() {}

    public static SMBClient create() {
        SmbConfig config = SmbConfig.builder()
                .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0,
                        SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                // NTLM only: the default SPNEGO authenticator would reference Kerberos/GSS classes
                // that do not exist on Android.
                .withAuthenticators(new NtlmAuthenticator.Factory())
                .withSecurityProvider(SECURITY)
                .withSocketFactory(new TimeoutSocketFactory())
                .withTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .withSoTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        return new SMBClient(config);
    }

    static Connection connect(SMBClient client, String host, int port, SmbDiagnostics.Call timing) throws IOException {
        CONNECT_TIMING.set(timing);
        try {
            timing.phase("connection initialization");
            return client.connect(host, port);
        } finally {
            CONNECT_TIMING.remove();
        }
    }

    /** smbj connects through createSocket(host, port) with no connect timeout; add one here. */
    private static final class TimeoutSocketFactory extends SocketFactory {
        @Override
        public Socket createSocket() {
            return new Socket();
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = new Socket();
            try {
                SmbDiagnostics.Call timing = CONNECT_TIMING.get();
                if (timing != null) timing.phase("resolve");
                InetSocketAddress address = new InetSocketAddress(host, port);
                if (timing != null) timing.phase("TCP connect");
                socket.connect(address, CONNECT_TIMEOUT_MS);
                if (timing != null) timing.phase("negotiate");
            } catch (IOException | RuntimeException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Original failure is more useful.
                }
                throw e;
            }
            return socket;
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort) throws IOException {
            return createSocket(host, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port) throws IOException {
            return createSocket(address.getHostAddress(), port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return createSocket(address.getHostAddress(), port);
        }
    }
}
