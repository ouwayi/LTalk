package cn.ltalk.common;

/** Local probe timing; peers need not use the same settings. */
public record HeartbeatSettings(int intervalMillis, int timeoutMillis) {
    public HeartbeatSettings {
        if (intervalMillis < 1 || timeoutMillis < 1)
            throw new IllegalArgumentException("心跳间隔和响应超时必须为正整数毫秒");
    }

    public static HeartbeatSettings fromSystemProperties() {
        return new HeartbeatSettings(property("ltalk.heartbeat.intervalMillis", 10_000),
                property("ltalk.heartbeat.timeoutMillis", 20_000));
    }

    private static int property(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException(name + " 必须为正整数毫秒", ex); }
    }
}
