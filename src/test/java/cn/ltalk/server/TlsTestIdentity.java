package cn.ltalk.server;

import cn.ltalk.common.TlsTransport;
import javax.net.ssl.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;

final class TlsTestIdentity {
    final SSLContext server;
    final SSLContext client;

    TlsTestIdentity(Path directory) throws Exception {
        Path storeFile = directory.resolve("test-identity.p12");
        char[] password = "test-store-only".toCharArray();
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        Process create = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-genkeypair", "-alias", "test-server", "-keyalg", "EC", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", storeFile.toString(), "-storepass", new String(password), "-noprompt")
                .redirectErrorStream(true).start();
        if (!create.waitFor(15, TimeUnit.SECONDS)) {
            create.destroyForcibly();
            throw new IllegalStateException("测试证书生成超时");
        }
        if (create.exitValue() != 0) throw new IllegalStateException(new String(create.getInputStream().readAllBytes()));
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storeFile)) { store.load(input, password); }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("test-server", store.getCertificate("test-server"));
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        client = SSLContext.getInstance("TLS");
        client.init(null, factory.getTrustManagers(), null);
        server = TlsTransport.serverContext(storeFile, password);
    }
}
