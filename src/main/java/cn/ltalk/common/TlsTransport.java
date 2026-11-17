package cn.ltalk.common;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;

public final class TlsTransport {
    private TlsTransport() { }

    public static SSLContext serverContext(Path keyStorePath, char[] password) throws IOException {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStorePath)) { store.load(input, password); }
            var aliases = store.aliases();
            boolean hasKey = false;
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (store.isKeyEntry(alias) && store.getCertificate(alias) != null) hasKey = true;
            }
            if (!hasKey) throw new IOException("PKCS12 文件缺少服务端私钥和证书");
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(store, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return context;
        } catch (GeneralSecurityException ex) {
            throw new IOException("无法加载 TLS 证书和私钥", ex);
        } finally { Arrays.fill(password, '\0'); }
    }

    public static SSLContext clientContext() throws IOException {
        try { return SSLContext.getDefault(); }
        catch (NoSuchAlgorithmException ex) { throw new IOException("无法加载客户端信任库", ex); }
    }

    public static SSLSocket layer(Socket raw, SSLContext context, String host, boolean client) throws IOException {
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(raw, host, raw.getPort(), false);
        socket.setUseClientMode(client);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
        if (client) parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        return socket;
    }
}
