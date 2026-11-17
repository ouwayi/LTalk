package cn.ltalk.client;

import cn.ltalk.common.*;
import cn.ltalk.server.ChatServer;
import cn.ltalk.support.Peer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class AttachmentIntegrationTest {
    @TempDir Path directory;

    @Test void desktopClientSendsImagesAndFilesAndDownloadsOriginalBytes() throws Exception {
        Path image = directory.resolve("原图.png");
        BufferedImage source = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 320, 200);
        graphics.dispose();
        assertTrue(ImageIO.write(source, "png", image.toFile()));
        BlockingQueue<ChatClient.State> states = new LinkedBlockingQueue<>();
        BlockingQueue<Message> events = new LinkedBlockingQueue<>();
        try (ChatServer server = new ChatServer("127.0.0.1", 0);
             ChatClient client = new ChatClient(new ClientEventListener() {
                 @Override public void onStateChanged(ChatClient.State state, String detail) { states.add(state); }
                 @Override public void onGroup(Message message) { events.add(message); }
             }, Runnable::run)) {
            server.dataDirectory(directory.resolve("server"));
            server.start();
            server.accounts().register("alice", "Alice", "test123!", false);
            client.connect("127.0.0.1", server.port(), "alice", "test123!");
            ChatClient.State state;
            do { state = states.poll(5, TimeUnit.SECONDS); assertNotNull(state); }
            while (state != ChatClient.State.CONNECTED);

            AttachmentChannel channel = client.attachments();
            for (Attachment.Kind kind : Attachment.Kind.values()) {
                var upload = run(done -> channel.start(AttachmentChannel.Request.upload(ConversationScope.PUBLIC, null, image, kind),
                        percent -> { }, done));
                assertFalse(upload.error(), upload.detail());
                Message message = events.poll(5, TimeUnit.SECONDS);
                assertNotNull(message);
                assertEquals("原图.png", message.content());
                assertEquals(kind, message.attachment().kind());
                if (kind == Attachment.Kind.IMAGE) {
                    assertNotNull(ChatImage.decodeThumbnail(message.attachment().thumbnail()));
                    var preview = run(done -> channel.start(AttachmentChannel.Request.download(ConversationScope.PUBLIC,
                            message.messageId(), message.attachment(), null, false), percent -> { }, done));
                    assertFalse(preview.error(), preview.detail());
                    assertEquals(320, preview.image().getWidth());
                    assertEquals(Color.BLUE.getRGB(), preview.image().getRGB(10, 10));
                } else assertNull(message.attachment().thumbnail());
                Path destination = directory.resolve(kind.name() + ".png");
                var download = run(done -> channel.start(AttachmentChannel.Request.download(ConversationScope.PUBLIC,
                        message.messageId(), message.attachment(), destination, false), percent -> { }, done));
                assertFalse(download.error(), download.detail());
                assertEquals(destination, download.saved());
                assertArrayEquals(Files.readAllBytes(image), Files.readAllBytes(destination));
                var collision = run(done -> channel.start(AttachmentChannel.Request.download(ConversationScope.PUBLIC,
                        message.messageId(), message.attachment(), destination, false), percent -> { }, done));
                assertTrue(collision.error());
                assertArrayEquals(Files.readAllBytes(image), Files.readAllBytes(destination));
            }

            Path fake = Files.writeString(directory.resolve("fake.png"), "plain text");
            var invalid = run(done -> channel.start(AttachmentChannel.Request.upload(ConversationScope.PUBLIC, null, fake,
                    Attachment.Kind.IMAGE), percent -> { }, done));
            assertTrue(invalid.error());
            assertTrue(invalid.detail().contains("图片无法读取"), invalid.detail());
            client.sendGroup("😀");
            assertEquals("😀", events.poll(5, TimeUnit.SECONDS).content());
        }
        try (ChatServer server = new ChatServer("127.0.0.1", 0)) {
            server.dataDirectory(directory.resolve("server"));
            server.start();
            try (Peer alice = Peer.login(server.port(), "alice")) {
                alice.send(Message.of(MessageType.HISTORY_VIEW_REQUEST).requestId("image-history")
                        .historyMode("SEARCH").conversationId(ConversationScope.PUBLIC).content("原图").build());
                assertNotNull(alice.type(MessageType.HISTORY_VIEW_ROW).record().attachment());
                assertNotNull(alice.type(MessageType.HISTORY_VIEW_ROW).record().attachment());
                alice.type(MessageType.HISTORY_VIEW_END);
            }
        }
    }

    @FunctionalInterface interface Start { void run(Consumer<AttachmentChannel.Result> done) throws Exception; }
    private static AttachmentChannel.Result run(Start start) throws Exception {
        var result = new CompletableFuture<AttachmentChannel.Result>();
        start.run(result::complete);
        return result.get(6, TimeUnit.SECONDS);
    }
}
