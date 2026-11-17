package cn.ltalk.common;

/** Bounded desktop archive views. Each response row fits a normal protocol frame. */
public enum HistoryQuery {
    SUMMARIES, MESSAGES, SEARCH, CONTEXT;

    public static final int PAGE_SIZE = 20;
    public static final int GROUP_JOIN_LIMIT = 100;
    public static final int CONTEXT_RADIUS = 9;
    public static final int QUERY_LIMIT = 128;
}
