package cn.ltalk.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeartbeatSettingsTest {
    @Test void rejectsInvalidTimingAndLoadsExplicitSystemProperties() {
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatSettings(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new HeartbeatSettings(1, -1));
        String interval = "ltalk.heartbeat.intervalMillis", timeout = "ltalk.heartbeat.timeoutMillis";
        String previousInterval = System.getProperty(interval), previousTimeout = System.getProperty(timeout);
        try {
            System.clearProperty(interval); System.clearProperty(timeout);
            assertEquals(new HeartbeatSettings(10_000, 20_000), HeartbeatSettings.fromSystemProperties());
            System.setProperty(interval, "120"); System.setProperty(timeout, "450");
            assertEquals(new HeartbeatSettings(120, 450), HeartbeatSettings.fromSystemProperties());
            for (String invalid : new String[]{"0", "-1", "abc", "2147483648", ""}) {
                System.setProperty(interval, invalid);
                assertThrows(IllegalArgumentException.class, HeartbeatSettings::fromSystemProperties);
            }
        } finally {
            if (previousInterval == null) System.clearProperty(interval); else System.setProperty(interval, previousInterval);
            if (previousTimeout == null) System.clearProperty(timeout); else System.setProperty(timeout, previousTimeout);
        }
    }

    @Test void heartbeatFramesRoundTripWithCorrelationId() throws Exception {
        for (MessageType type : new MessageType[]{MessageType.PING, MessageType.PONG}) {
            Message frame = Message.of(type).requestId("h23").build();
            assertEquals(frame, MessageCodec.decode(MessageCodec.encode(frame)));
        }
    }
}
