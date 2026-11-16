package cn.ltalk.server;

import cn.ltalk.common.*;

import java.io.IOException;
import java.net.*;
import java.time.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    private java.nio.file.Path dataDirectory;
    private java.nio.channels.FileChannel dataLockChannel;
    private java.nio.channels.FileLock dataLock;
    public AccountStore accounts() { return accounts; }
    ChatArchive archive() { return archive; }
    public synchronized void dataDirectory(java.nio.file.Path path) {
        if (listener != null) throw new IllegalStateException("启动后不能更改数据目录");
        dataDirectory = path;
    }
    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final SessionRegistry registry = new SessionRegistry();
    private final MessageRouter router = new MessageRouter(this, registry);
    private final Semaphore slots;
    private final ExecutorService workers;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(threadFactory("ltalk-login-"));
    private volatile boolean running;
    private boolean stopped;
    private ServerSocket listener;
    private Thread acceptThread;
    private int discoveryPort;
    private String discoveryName;
    private DiscoveryResponder discovery;

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
            accounts = new AccountStore(dataDirectory.resolve("accounts"));
            archive = new ChatArchive(dataDirectory.resolve("history"));
        } catch (IOException | RuntimeException ex) {
            dataLockChannel.close(); throw new IOException("无法打开服务端数据目录", ex);
        }
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(bindAddress, requestedPort));
        } catch (IOException ex) { socket.close(); throw ex; }
        listener = socket;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ltalk-accept");
        acceptThread.start();
        LOG.info(() -> "服务端监听 " + listener.getLocalSocketAddress() + "，最大连接数=" + maxConnections);
        if (discoveryPort != 0) {
            try {
                discovery = new DiscoveryResponder(bindAddress, socket.getLocalPort(), discoveryPort, discoveryName);
                LOG.info(() -> "局域网发现 UDP " + discoveryPort + " · " + discoveryName);
            } catch (IOException ex) {
                LOG.warning("局域网发现未启动，仍可手动连接：" + ex.getMessage());
            }
        }
    }

    public synchronized int port() { return listener == null ? requestedPort : listener.getLocalPort(); }
    public int connectionCount() { return sessions.size(); }
    public int onlineCount() { return registry.readySessions().size(); }
    MessageRouter router() { return router; }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = listener.accept();
                // Admission and shutdown cannot miss a socket between accepting and registering it.
                synchronized (this) {
                    if (!running || !slots.tryAcquire()) { socket.close(); continue; }
                    ClientSession session = new ClientSession(this, socket, heartbeatSettings);
                    sessions.add(session);
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
        try { session.send(message); return true; }
        catch (IOException ex) { session.close("写入失败：" + ex.getMessage()); return false; }
    }

    void broadcast(Message message) {
        for (ClientSession session : registry.readySessions()) deliver(session, message);
    }

    void system(String text) {
        broadcast(Message.of(MessageType.SYSTEM).content(text).serverTime(Instant.now().toString()).build());
    }

    void sessionClosed(ClientSession session, String reason) {
        if (sessions.remove(session)) slots.release();
        Message list = registry.remove(session);
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
            for (ClientSession session : closing) session.awaitHeartbeatTermination();
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        try { if (dataLock != null && dataLock.isValid()) dataLock.release(); } catch (IOException ignored) { }
        try { if (dataLockChannel != null) dataLockChannel.close(); } catch (IOException ignored) { }
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
            int max = args.length > 2 ? Integer.parseInt(args[2]) : 20;
            ChatServer server = new ChatServer(bind, port, max, Duration.ofSeconds(10));
            server.dataDirectory(java.nio.file.Path.of(System.getProperty("ltalk.dataDir", "data")));
            if (Boolean.parseBoolean(System.getProperty("ltalk.discovery.enabled", "true"))) {
                try { server.enableDiscovery(LanDiscovery.configuredPort(), System.getProperty("ltalk.discovery.name", "LTalk")); }
                catch (IllegalArgumentException ex) { LOG.warning("局域网发现配置无效，仍可手动连接：" + ex.getMessage()); }
            }
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ltalk-shutdown"));
            server.start();
            System.out.println("LTalk 已启动，按 Ctrl+C 停止。客户端应连接本机或局域网 IPv4 地址。");
        } catch (IOException | IllegalArgumentException ex) {
            System.err.println("服务端启动失败：" + ex.getMessage() + "（检查端口是否占用、地址是否存在）");
            usage();
            System.exit(1);
        }
    }

    private static void usage() { System.err.println("用法：java -jar target/ltalk-1.0.0.jar [端口=9000] [监听IP=0.0.0.0] [最大连接数=20]"); }
}
