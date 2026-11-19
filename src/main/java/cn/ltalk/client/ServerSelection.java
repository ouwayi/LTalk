package cn.ltalk.client;

import cn.ltalk.client.ui.ConnectionPanel;

import javax.swing.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.logging.*;

/** Coordinates server selection on the EDT. UDP and preference I/O run in background workers. */
final class ServerSelection {
    private static final Logger LOG = Logger.getLogger(ServerSelection.class.getName());
    private static final ExecutorService RECENT_IO = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "ltalk-recent-servers");
        worker.setDaemon(true);
        return worker;
    });
    private final ConnectionPanel panel;
    private final RecentServers recent;
    private final Callable<List<DiscoveryClient.Candidate>> discover;
    private final BooleanSupplier available;
    private final Runnable connect;
    private SwingWorker<List<DiscoveryClient.Candidate>, Void> scan;
    private int revision;

    ServerSelection(ConnectionPanel panel, RecentServers recent, Callable<List<DiscoveryClient.Candidate>> discover,
                    BooleanSupplier available, Runnable connect) {
        this.panel = panel;
        this.recent = recent;
        this.discover = discover;
        this.available = available;
        this.connect = connect;
        panel.discover.addActionListener(e -> {
            if (scan == null) startDiscovery();
            else editedAddress();
        });
        panel.recent.addActionListener(e -> showRecent());
        panel.choices.addActionListener(e -> {
            if (available.getAsBoolean() && panel.choices.getSelectedItem() instanceof ConnectionPanel.ServerChoice choice) {
                fill(choice.host(), choice.port(), choice.tls());
                panel.feedback("已选服务器，可点击连接");
            }
        });
    }

    void startDiscovery() {
        if (!available.getAsBoolean() || scan != null) return;
        if (panel.tls.isSelected()) {
            panel.feedback("请填写地址，或选择最近连接的服务器");
            return;
        }
        revision++;
        panel.clearChoices();
        panel.scanning(true);
        panel.feedback("正在查找局域网服务器…");
        scan = new SwingWorker<>() {
            @Override protected List<DiscoveryClient.Candidate> doInBackground() throws Exception { return discover.call(); }

            @Override protected void done() {
                if (scan != this) return;
                scan = null;
                panel.scanning(false);
                if (!available.getAsBoolean()) return;
                try { showDiscovered(get()); }
                catch (ExecutionException ex) {
                    LOG.log(Level.FINE, "局域网发现失败", ex.getCause());
                    panel.feedback("查找失败，可手动连接");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        scan.execute();
    }

    private void showDiscovered(List<DiscoveryClient.Candidate> candidates) {
        if (candidates.isEmpty()) {
            panel.feedback("未找到局域网服务器，可手动连接");
        } else if (candidates.size() == 1) {
            ClientSessions.Address address = candidates.get(0).address();
            fill(address.host(), address.port(), address.tls());
            connect.run();
        } else {
            panel.showChoices("发现的局域网服务器", candidates.stream()
                    .map(candidate -> choice(candidate.name(), candidate.address())).toList());
            panel.feedback("找到 " + candidates.size() + " 台服务器，请选择");
        }
    }

    void editedAddress() {
        boolean scanning = scan != null;
        cancel();
        if (scanning) panel.feedback("查找已取消，可手动连接");
    }

    void cancel() {
        revision++;
        SwingWorker<?, ?> task = scan;
        if (task == null) return;
        scan = null;
        task.cancel(true);
        panel.scanning(false);
    }

    void remember(ClientSessions.Address address) {
        if (address == null) return;
        RECENT_IO.execute(() -> {
            try { recent.remember(address); }
            catch (IOException ex) { LOG.log(Level.FINE, "无法保存最近服务器", ex); }
        });
    }

    private void showRecent() {
        if (!available.getAsBoolean()) return;
        cancel();
        panel.clearChoices();
        int request = revision;
        RECENT_IO.execute(() -> {
            List<ClientSessions.Address> addresses = recent.load();
            SwingUtilities.invokeLater(() -> {
                if (request != revision || !available.getAsBoolean()) return;
                if (addresses.isEmpty()) {
                    panel.feedback("暂无最近连接的服务器");
                } else {
                    panel.showChoices("最近连接的服务器", addresses.stream().map(address -> choice("", address)).toList());
                    panel.feedback("请选择最近连接的服务器");
                }
            });
        });
    }

    private void fill(String host, int port, boolean tls) {
        panel.host.setText(host);
        panel.port.setText(Integer.toString(port));
        panel.tls.setSelected(tls);
    }

    private static ConnectionPanel.ServerChoice choice(String name, ClientSessions.Address address) {
        return new ConnectionPanel.ServerChoice(name, address.host(), address.port(), address.tls());
    }
}
