package cn.ltalk.server;

import cn.ltalk.common.Message;
import cn.ltalk.common.MessageType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** The short monitor protects membership, readiness, revision and snapshot as one operation. No I/O here. */
final class SessionRegistry {
    private final Map<String, ClientSession> users = new ConcurrentHashMap<>();
    private long revision;

    synchronized boolean reserve(String userId, ClientSession session) {
        if (session.isClosed() || users.putIfAbsent(userId, session) != null) return false;
        session.userId = userId;
        return true;
    }

    synchronized Message activate(ClientSession session) {
        if (session.isClosed() || users.get(session.userId) != session) return null;
        session.ready = true;
        revision++;
        return snapshot();
    }

    synchronized Message remove(ClientSession session) {
        if (session.userId == null || !users.remove(session.userId, session)) return null;
        if (!session.ready) return null;
        session.ready = false;
        revision++;
        return snapshot();
    }

    synchronized ClientSession findReady(String userId) {
        ClientSession session = users.get(userId);
        return session != null && session.ready && !session.isClosed() ? session : null;
    }

    synchronized List<ClientSession> readySessions() {
        return users.values().stream().filter(s -> s.ready && !s.isClosed()).toList();
    }

    private Message snapshot() {
        List<String> names = users.entrySet().stream().filter(e -> e.getValue().ready)
                .map(Map.Entry::getKey).sorted().toList();
        return Message.of(MessageType.USER_LIST).users(names).revision(revision).build();
    }
}
