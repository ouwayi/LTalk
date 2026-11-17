package cn.ltalk.server;

import cn.ltalk.common.*;
import javax.net.ssl.SSLContext;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;

/** Shared admission and authentication limits for a public TLS listener. */
record PublicAccess(SSLContext tls, int maxUnauthenticated, int connectionsPerIp, int loginsPerIp,
                    int registrationsPerIp, int loginsPerAccount, int hashingWorkers, int authSeconds) {
    PublicAccess {
        Objects.requireNonNull(tls);
        if (maxUnauthenticated < 1 || connectionsPerIp < 1 || loginsPerIp < 1 || registrationsPerIp < 1
                || loginsPerAccount < 1 || hashingWorkers < 1 || authSeconds < 1)
            throw new IllegalArgumentException("公网连接限制必须为正整数");
    }

    static PublicAccess configured(SSLContext tls) {
        return new PublicAccess(tls, setting("unauthenticated", 24), setting("connectionsPerIp", 60),
                setting("loginsPerIp", 24), setting("registrationsPerIp", 4), setting("loginsPerAccount", 8),
                setting("hashingWorkers", Math.min(4, Runtime.getRuntime().availableProcessors())), setting("authSeconds", 60));
    }

    private static int setting(String key, int fallback) {
        return Integer.parseInt(System.getProperty("ltalk.public." + key, Integer.toString(fallback)));
    }

    static final class Limits {
        private final PublicAccess policy;
        private final Semaphore hashes;
        private final Rate connections = new Rate(), logins = new Rate(), registrations = new Rate(), accounts = new Rate();

        Limits(PublicAccess policy) { this.policy = policy; hashes = new Semaphore(policy.hashingWorkers(), true); }

        boolean connection(InetAddress ip) { return connections.allow(ip.getHostAddress(), policy.connectionsPerIp()); }

        void acquire(ClientSession session, Message request) throws ProtocolException {
            String ip = session.remoteAddress().getHostAddress();
            boolean allowed = switch (request.type()) {
                case REGISTER -> registrations.allow(ip, policy.registrationsPerIp());
                case LOGIN -> logins.allow(ip, policy.loginsPerIp())
                        && accounts.allow(Validation.userId(request.userId()), policy.loginsPerAccount());
                case PASSWORD_CHANGE -> accounts.allow(session.userId, policy.loginsPerAccount());
                default -> throw new IllegalArgumentException("认证请求类型不正确");
            };
            if (!allowed) throw new ProtocolException("RATE_LIMITED", "认证请求过于频繁，请稍后再试");
            try {
                if (!hashes.tryAcquire(2, TimeUnit.SECONDS)) throw busy();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw busy();
            }
        }

        private static ProtocolException busy() { return new ProtocolException("SERVER_BUSY", "认证服务繁忙，请稍后再试"); }
        void release() { hashes.release(); }
    }

    private static final class Rate {
        private static final int MAX_KEYS = 4096;
        private static final long MINUTE = TimeUnit.MINUTES.toNanos(1);
        private record Count(long start, int requests) { }
        private final Map<String, Count> counts = new LinkedHashMap<>();

        synchronized boolean allow(String key, int limit) {
            long now = System.nanoTime();
            var oldest = counts.values().iterator();
            while (oldest.hasNext() && now - oldest.next().start() >= MINUTE) oldest.remove();
            Count current = counts.get(key);
            if (current == null) {
                if (counts.size() == MAX_KEYS) return false;
                counts.put(key, new Count(now, 1));
                return true;
            }
            if (current.requests() >= limit) return false;
            counts.put(key, new Count(current.start(), current.requests() + 1));
            return true;
        }
    }
}
