package cn.ltalk.client;

import cn.ltalk.common.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class HistoryClientTest {
    @TempDir Path directory;
    @Test void clientPagesAndExportsIndependentlyAndDeletedHistoryStaysGoneOnReconnect() throws Exception {
        BlockingQueue<ChatClient.State> states = new LinkedBlockingQueue<>();
        List<ChatRecord> records = new CopyOnWriteArrayList<>(); BlockingQueue<Boolean> completed = new LinkedBlockingQueue<>(), deleted = new LinkedBlockingQueue<>();
        BlockingQueue<Path> exports = new LinkedBlockingQueue<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.start(); Peer.seed(server);
            try (ChatClient client = new ChatClient(new ClientEventListener() {
                public void onStateChanged(ChatClient.State state,String detail) { if(state!=ChatClient.State.CONNECTING) states.add(state); }
                public void onHistoryStarted() { records.clear(); }
                public void onHistoryRecord(ChatRecord record) { records.add(record); }
                public void onHistoryComplete() { completed.add(true); }
                public void onHistoryDeleted(String scope,String id) { deleted.add(true); }
                public void onExported(Path path) { exports.add(path); }
            },Runnable::run); Peer bob = Peer.login(server.port(),"bob")) {
                assertThrows(java.io.IOException.class,client::loadHistory);
                client.connect("127.0.0.1",server.port(),"alice","test123!"); assertEquals(ChatClient.State.CONNECTED,states.poll(5,TimeUnit.SECONDS));
                bob.group("group"); bob.type(MessageType.GROUP_MESSAGE); bob.privateTo("alice","private"); bob.type(MessageType.PRIVATE_MESSAGE);
                client.loadHistory(); client.exportTo(directory.resolve("custom.json"),"json",ConversationScope.PUBLIC,false);
                assertNotNull(completed.poll(5,TimeUnit.SECONDS)); assertEquals(2,records.size()); assertNotNull(exports.poll(5,TimeUnit.SECONDS));
                assertFalse(Files.readString(directory.resolve("custom.json")).contains("private"));
                String groupId = records.stream().filter(r -> r.content().equals("group")).findFirst().orElseThrow().messageId();
                client.deleteHistory(ConversationScope.PUBLIC,groupId); assertNotNull(deleted.poll(5,TimeUnit.SECONDS));
                client.loadHistory(); assertNotNull(completed.poll(5,TimeUnit.SECONDS)); assertEquals(List.of("private"),records.stream().map(ChatRecord::content).toList());
                client.disconnect(); assertEquals(ChatClient.State.DISCONNECTED,states.poll(5,TimeUnit.SECONDS));
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4); while(server.onlineCount()!=1 && System.nanoTime()<end) Thread.sleep(10);
                client.connect("127.0.0.1",server.port(),"alice","test123!"); assertEquals(ChatClient.State.CONNECTED,states.poll(5,TimeUnit.SECONDS));
                client.loadHistory(); assertNotNull(completed.poll(5,TimeUnit.SECONDS)); assertEquals(1,records.size());
                client.deleteHistory(ConversationScope.privateChat("alice","bob"),null); assertNotNull(deleted.poll(5,TimeUnit.SECONDS));
                client.loadHistory(); assertNotNull(completed.poll(5,TimeUnit.SECONDS)); assertTrue(records.isEmpty());
            }
        }
    }
    @Test void historyTimeoutAndCancelledQueuedPagesNeverApplyStaleRecords() throws Exception {
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        Queue<Message> sent=new ConcurrentLinkedQueue<>(); Queue<Runnable> callbacks=new ConcurrentLinkedQueue<>();
        List<ChatRecord> records=new ArrayList<>(); List<Message> errors=new ArrayList<>(); java.util.concurrent.atomic.AtomicInteger ids=new java.util.concurrent.atomic.AtomicInteger();
        HistoryChannel channel=new HistoryChannel(sent::add,callbacks::add,new ClientEventListener() {
            public void onHistoryRecord(ChatRecord record) { records.add(record); }
            public void onError(Message error) { errors.add(error); }
        },timer,()->"r"+ids.incrementAndGet(),()->"alice",120);
        try {
            channel.load(); Message first=sent.remove();
            ChatRecord record=new ChatRecord("id","GROUP:PUBLIC","bob",null,"old","GROUP_MESSAGE","2026-11-01T00:00:00Z");
            channel.receive(Message.of(MessageType.HISTORY_PAGE).requestId(first.requestId()).cursor("cursor").record(record).build());
            assertTrue(sent.isEmpty(),"Do not request another page before the UI has consumed this one");
            channel.load(); Message fresh=sent.remove();
            channel.receive(Message.of(MessageType.HISTORY_END).requestId(first.requestId()).build());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(errors.isEmpty() && System.nanoTime()<end) { Runnable task; while((task=callbacks.poll())!=null) task.run(); Thread.sleep(10); }
            assertTrue(records.isEmpty()); assertEquals("HISTORY_FAILED",errors.get(0).code());
            channel.receive(Message.of(MessageType.HISTORY_PAGE).requestId(fresh.requestId()).cursor("late").record(record).build());
            channel.delete(ConversationScope.PUBLIC,null); sent.remove();
            end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(errors.size()<2 && System.nanoTime()<end) { Runnable task; while((task=callbacks.poll())!=null) task.run(); Thread.sleep(10); }
            assertEquals("HISTORY_DELETE_FAILED",errors.get(1).code()); assertTrue(records.isEmpty());
            channel.load(); Message last=sent.remove(); channel.receive(Message.of(MessageType.HISTORY_PAGE).requestId(last.requestId()).cursor("c").record(record).build());
            channel.close(); Runnable task; while((task=callbacks.poll())!=null) task.run(); assertTrue(records.isEmpty());
        } finally { channel.close(); timer.shutdownNow(); }
    }
}
