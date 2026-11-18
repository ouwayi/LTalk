package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.time.*;
import java.nio.file.*;
import javax.net.ssl.SSLSocket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.*;

public final class ChatServer implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ChatServer.class.getName());
    private final InetAddress bindAddress;
    private final int requestedPort;
    private final int maxConnections;
    private final Duration loginTimeout;
    private final HeartbeatSettings heartbeatSettings;
    private AccountStore accounts;
    private ChatArchive archive;
    private SocialStore social;
    private AttachmentStore attachments;
    private java.nio.file.Path dataDirectory;
    private SqliteDatabase database;
    private java.nio.channels.FileChannel dataLockChannel;
    private java.nio.channels.FileLock dataLock;
    public AccountStore accounts() { return accounts; }
    ChatArchive archive() { return archive; }
    SocialStore social() { return social; }
    AttachmentStore attachments() { return attachments; }
    public synchronized void dataDirectory(java.nio.file.Path path) {
        if (listener != null) throw new IllegalStateException("启动后不能更改数据目录");
        dataDirectory = path;
    }
    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final SessionRegistry registry = new SessionRegistry();
    private final MessageRouter router = new MessageRouter(this, registry);
    private final Semaphore slots;
    private final ExecutorService workers;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, threadFactory("ltalk-login-"));
    private volatile boolean running;
    private boolean stopped;
    private ServerSocket listener;
    private Thread acceptThread;
    private int discoveryPort;
    private String discoveryName;
    private DiscoveryResponder discovery;
    private PublicAccess publicAccess;
    private PublicAccess.Limits limits;
    private int unauthenticated;
    private final LongAdder admissionsRejected = new LongAdder();
    private final LongAdder slowClients = new LongAdder();
    private final LongAdder authRejected = new LongAdder();

    synchronized void publicAccess(PublicAccess policy) {
        if (stopped || listener != null) throw new IllegalStateException("启动后不能更改 TLS 设置");
        publicAccess = policy;
        limits = new PublicAccess.Limits(policy);
    }

    public synchronized void enableDiscovery(int port, String name) {
        if (stopped || listener != null) throw new IllegalStateException("启动后不能更改发现设置");
        if (!LanDiscovery.validPort(port)) throw new IllegalArgumentException("发现端口必须为 1～65535");
        discoveryName = LanDiscovery.validateName(name);
        discoveryPort = port;
    }

    public ChatServer(String bindAddress, int port) throws UnknownHostException {
        this(bindAddress, port, 20, Duration.ofSeconds(10));
    }

    public ChatServer(String bindAddress, int port, int maxConnections, Duration loginTimeout) throws UnknownHostException {
        this(bindAddress, port, maxConnections, loginTimeout, HeartbeatSettings.fromSystemProperties());
    }

    public ChatServer(String bindAddress, int port, int maxConnections, Duration loginTimeout,
                      HeartbeatSettings heartbeatSettings) throws UnknownHostException {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("端口必须为 0～65535");
        if (maxConnections < 1 || maxConnections > 1000) throw new IllegalArgumentException("最大连接数须为 1～1000");
        if (loginTimeout.isNegative() || loginTimeout.isZero()) throw new IllegalArgumentException("登录超时必须为正数");
        this.bindAddress = InetAddress.getByName(bindAddress);
        this.requestedPort = port;
        this.maxConnections = maxConnections;
        this.loginTimeout = loginTimeout;
        this.heartbeatSettings = java.util.Objects.requireNonNull(heartbeatSettings);
        slots = new Semaphore(maxConnections);
        deadlines.setRemoveOnCancelPolicy(true);
        ThreadPoolExecutor boundedWorkers = new ThreadPoolExecutor(maxConnections, maxConnections, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxConnections), threadFactory("ltalk-session-"), new ThreadPoolExecutor.AbortPolicy());
        boundedWorkers.allowCoreThreadTimeOut(true);
        workers = boundedWorkers;
    }

    public synchronized void start() throws IOException {
        if (stopped || listener != null) throw new IllegalStateException("请为每次启动创建新的 ChatServer");
        if (dataDirectory == null) dataDirectory = java.nio.file.Files.createTempDirectory("ltalk-server-");
        java.nio.file.Files.createDirectories(dataDirectory);
        dataLockChannel = java.nio.channels.FileChannel.open(dataDirectory.resolve("server.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
        try {
            dataLock = dataLockChannel.tryLock();
            if (dataLock == null) throw new IOException("数据目录已被另一个服务端使用");
            database = new SqliteDatabase(dataDirectory);
            accounts = new AccountStore(database);
            archive = new ChatArchive(database);
            social = new SocialStore(database, archive);
            attachments = new AttachmentStore(this, dataDirectory.resolve("attachments"));
        } catch (IOException | RuntimeException ex) {
            closeData();
            throw new IOException("无法打开服务端数据目录：" + ex.getMessage(), ex);
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress, requestedPort));
        } catch (IOException ex) {
            try { socket.close(); } catch (IOException closeFailure) { ex.addSuppressed(closeFailure); }
            closeData();
            throw ex;
        }
        listener = socket;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ltalk-accept");
        acceptThread.start();
        LOG.info(() -> "服务端监听 " + listener.getLocalSocketAddress() + "，最大连接数=" + maxConnections);
        if (discoveryPort != 0 && publicAccess == null) {
            try {
                discovery = new DiscoveryResponder(bindAddress, socket.getLocalPort(), discoveryPort, discoveryName);
                LOG.info(() -> "局域网发现 UDP " + discoveryPort + " · " + discoveryName);
            } catch (IOException ex) {
                LOG.warning("局域网发现未启动，仍可手动连接：" + ex.getMessage());
            }
        }
        if (publicAccess != null) deadlines.scheduleWithFixedDelay(this::logStatus, 60, 60, TimeUnit.SECONDS);
    }

    public synchronized int port() { return listener == null ? requestedPort : listener.getLocalPort(); }
    public int connectionCount() { return sessions.size(); }
    public int onlineCount() { return registry.readySessions().size(); }
    long slowClientCount() { return slowClients.sum(); }
    long admissionRejectedCount() { return admissionsRejected.sum(); }
    MessageRouter router() { return router; }

    Socket transport(Socket raw) throws IOException {
        if (publicAccess == null) return raw;
        SSLSocket tls = TlsTransport.layer(raw, publicAccess.tls(), raw.getInetAddress().getHostAddress(), false);
        tls.startHandshake();
        return tls;
    }

    ScheduledFuture<?> transferDeadline(ClientSession session) {
        return deadlines.schedule(() -> session.close("文件传输超时"), Attachment.TRANSFER_SECONDS, TimeUnit.SECONDS);
    }

    void greeted(ClientSession session) {
        if (session.loginDeadline != null) session.loginDeadline.cancel(false);
        if (publicAccess != null) session.loginDeadline = deadlines.schedule(() -> {
            if (!session.ready) session.close("认证超时");
        }, publicAccess.authSeconds(), TimeUnit.SECONDS);
    }

    synchronized void authenticated(ClientSession session) {
        if (session.unauthenticated) {
            unauthenticated--;
            session.unauthenticated = false;
        }
    }

    @FunctionalInterface interface AuthAction<T> { T run() throws IOException; }

    <T> T authenticate(ClientSession session, Message request, AuthAction<T> action) throws IOException {
        if (limits == null) return action.run();
        try { limits.acquire(session, request); }
        catch (ProtocolException ex) { authRejected.increment(); throw ex; }
        try {
            if (session.isClosed()) throw new ProtocolException("NOT_AUTHENTICATED", "连接已关闭");
            return action.run();
        } finally { limits.release(); }
    }

    private void logStatus() {
        long heapMiB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        LOG.info(() -> "在线=" + onlineCount() + "，连接=" + connectionCount() + "，拒绝连接=" + admissionsRejected.sum()
                + "，认证限流/繁忙=" + authRejected.sum() + "，慢端淘汰=" + slowClients.sum() + "，堆 MiB=" + heapMiB);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = listener.accept();
                // Admission and shutdown cannot miss a socket between accepting and registering it.
                synchronized (this) {
                    if (!running || (publicAccess != null && (unauthenticated >= publicAccess.maxUnauthenticated()
                            || !limits.connection(socket.getInetAddress()))) || !slots.tryAcquire()) {
                        admissionsRejected.increment();
                        socket.close();
                        continue;
                    }
                    ClientSession session = new ClientSession(this, socket, heartbeatSettings);
                    sessions.add(session);
                    unauthenticated++;
                    LOG.fine(() -> "接受连接 " + socket.getRemoteSocketAddress());
                    try {
                        socket.setTcpNoDelay(true);
                        session.loginDeadline = deadlines.schedule(session::loginTimedOut, loginTimeout.toMillis(), TimeUnit.MILLISECONDS);
                        workers.execute(session);
                    } catch (IOException | RejectedExecutionException ex) {
                        session.close("初始化连接失败");
                    }
                }
            } catch (IOException ex) {
                if (running) LOG.log(Level.WARNING, "接受连接失败", ex);
            }
        }
    }

    boolean deliver(ClientSession session, Message message) {
        byte[] frame = encode(message);
        return frame != null && enqueue(session, message, frame, null);
    }

    boolean deliverAndWait(ClientSession session, Message message) {
        CompletableFuture<Void> written = new CompletableFuture<>();
        byte[] frame = encode(message);
        if (frame == null || !enqueue(session, message, frame, written)) return false;
        try {
            written.get(5, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ex) {
            LOG.fine("连接写入未完成");
        }
        return false;
    }

    private boolean enqueue(ClientSession session, Message message, byte[] frame, CompletableFuture<Void> written) {
        if (session.send(message, frame, written)) return true;
        if (session.evict()) {
            slowClients.increment();
            try { deadlines.execute(() -> session.close("发送队列已满，关闭慢连接")); }
            catch (RejectedExecutionException ignored) { /* Shutdown closes all sessions. */ }
        }
        return false;
    }

    private byte[] encode(Message message) {
        try { return MessageCodec.frame(message); }
        catch (ProtocolException ex) {
            LOG.warning("服务端消息无法编码：" + message.type() + " " + ex.code());
            return null;
        }
    }

    void broadcast(Message message) {
        broadcast(message, registry.readySessions());
    }

    void broadcast(Message message, List<ClientSession> recipients) {
        byte[] frame = encode(message);
        if (frame == null) return;
        for (ClientSession session : recipients) enqueue(session, message, frame, null);
    }

    void system(String text) {
        broadcast(Message.of(MessageType.SYSTEM).content(text).serverTime(Instant.now().toString()).build());
    }

    void sessionClosed(ClientSession session, String reason) {
        attachments.cancel(session, null);
        Message list = registry.remove(session);
        synchronized (this) {
            if (sessions.remove(session)) {
                authenticated(session);
                slots.release();
            }
        }
        LOG.info(() -> "连接退出 user=" + session + " reason=" + reason);
        if (list != null && running) {
            broadcast(list);
            system(session.userId + " 下线了");
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (stopped) return;
            stopped = true;
            running = false;
            if (discovery != null) discovery.close();
            if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        }
        ClientSession[] closing = sessions.toArray(ClientSession[]::new);
        for (ClientSession session : closing) session.close("服务端停止");
        workers.shutdownNow();
        deadlines.shutdownNow();
        try {
            if (discovery != null) discovery.awaitTermination();
            if (acceptThread != null) acceptThread.join(2000);
            workers.awaitTermination(2, TimeUnit.SECONDS);
            deadlines.awaitTermination(2, TimeUnit.SECONDS);
            for (ClientSession session : closing) session.awaitTermination();
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        closeData();
    }

    private void closeData() {
        try { if (database != null) database.close(); } catch (IOException ex) { LOG.warning(ex.getMessage()); }
        try { if (dataLockChannel != null) dataLockChannel.close(); } catch (IOException ex) { LOG.warning(ex.getMessage()); }
        database = null;
        dataLockChannel = null;
        dataLock = null;
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> new Thread(task, prefix + sequence.incrementAndGet());
    }

    public static void main(String[] args) {
        if (args.length > 3) { usage(); System.exit(1); }
        try {
            int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
            if (port < 1 || port > 65535) throw new IllegalArgumentException("命令行端口必须为 1～65535");
            String bind = args.length > 1 ? args[1] : "0.0.0.0";
            String tlsMode = System.getProperty("ltalk.tls.enabled", "false");
            if (!tlsMode.equalsIgnoreCase("true") && !tlsMode.equalsIgnoreCase("false"))
                throw new IllegalArgumentException("ltalk.tls.enabled 须为 true 或 false");
            boolean tls = Boolean.parseBoolean(tlsMode);
            int max = args.length > 2 ? Integer.parseInt(args[2]) : tls ? 140 : 20;
            ChatServer server = new ChatServer(bind, port, max, Duration.ofSeconds(10));
            server.dataDirectory(java.nio.file.Path.of(System.getProperty("ltalk.dataDir", "data")));
            if (tls) {
                String keyStore = System.getProperty("ltalk.tls.keyStore");
                String passwordFile = System.getProperty("ltalk.tls.passwordFile");
                if (keyStore == null || passwordFile == null)
                    throw new IllegalArgumentException("TLS 需要设置 PKCS12 路径和密码文件路径");
                String password = Files.readString(Path.of(passwordFile)).lines().findFirst().orElse("");
                if (password.isEmpty()) throw new IllegalArgumentException("TLS 密码文件为空");
                server.publicAccess(PublicAccess.configured(TlsTransport.serverContext(Path.of(keyStore), password.toCharArray())));
            } else if (Boolean.parseBoolean(System.getProperty("ltalk.discovery.enabled", "true"))) {
                try { server.enableDiscovery(LanDiscovery.configuredPort(), System.getProperty("ltalk.discovery.name", "LTalk")); }
                catch (IllegalArgumentException ex) { LOG.warning("局域网发现配置无效，仍可手动连接：" + ex.getMessage()); }
            }
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ltalk-shutdown"));
            server.start();
            System.out.println(tls ? "LTalk TLS 已启动，客户端须启用 TLS 并填写证书中的主机名。"
                    : "LTalk 局域网服务已启动，按 Ctrl+C 停止。");
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("服务端启动失败：" + ex.getMessage() + "（检查端口是否占用、地址是否存在）");
            usage();
            System.exit(1);
        }
    }

    private static void usage() { System.err.println("用法：java -jar target/ltalk-1.0.0.jar [端口=9000] [监听IP=0.0.0.0] [最大连接数=20]"); }
}
