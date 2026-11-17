package cn.ltalk.client;

import cn.ltalk.common.ChatRecord;

import java.util.List;

record HistoryPage(List<Row> rows, String next, String error) {
    record Row(ChatRecord record, String nickname) { }

    static HistoryPage failed(String detail) { return new HistoryPage(List.of(), null, detail); }
}
