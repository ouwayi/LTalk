package cn.ltalk.server;

import cn.ltalk.common.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Caches account reads and publishes changes only after the database commits. */
public final class AccountStore {
    private record Account(String userId, String nickname, String salt, String hash, String avatar, String createdAt, long profileRevision) { }
    record DisplayProfile(String nickname, String avatar, long revision) { }
    private final SqliteDatabase database;
    private final Map<String, Account> accounts = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    AccountStore(SqliteDatabase database) throws IOException {
        this.database = database;
        database.read(connection -> {
            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery("SELECT * FROM accounts")) {
                while (rows.next()) {
                    Account a = new Account(rows.getString("user_id"), rows.getString("nickname"),
                            rows.getString("salt"), rows.getString("hash"), rows.getString("avatar"),
                            rows.getString("created_at"), rows.getLong("profile_revision"));
                    Validation.userId(a.userId());
                    if (Base64.getDecoder().decode(a.salt()).length != 16
                            || Base64.getDecoder().decode(a.hash()).length != 32) throw new IOException("账号数据损坏");
                    AvatarData.decode(a.avatar());
                    accounts.put(a.userId(), a);
                }
            } catch (IllegalArgumentException ex) {
                throw new IOException("账号数据损坏", ex);
            }
            return null;
        });
    }
    public String register(String id, String nickname, String password, boolean generated) throws IOException {
        if (nickname == null || nickname.isBlank()) throw new ProtocolException("MISSING_FIELD", "请填写昵称");
        if (nickname.strip().length() > 40) throw new ProtocolException("INVALID_NICKNAME", "昵称最多 40 字符");
        validatePassword(password);
        if (!generated) {
            if (id == null || id.isBlank()) throw new ProtocolException("MISSING_FIELD", "请填写用户名");
            id = Validation.userId(id);
            if (lookup(id) != null) throw new ProtocolException("USER_EXISTS", "用户名已注册");
        }
        byte[] salt = new byte[16]; random.nextBytes(salt);
        var base64 = Base64.getEncoder();
        String digest = base64.encodeToString(hash(password, salt));
        synchronized (this) {
            if (generated) {
                do { id = "u_" + HexFormat.of().formatHex(random.generateSeed(8)); } while (accounts.containsKey(id));
            } else if (accounts.containsKey(id)) throw new ProtocolException("USER_EXISTS", "用户名已注册");
            save(new Account(id, nickname.strip(), base64.encodeToString(salt), digest, "", java.time.Instant.now().toString(), 0), true);
            return id;
        }
    }
    private void save(Account a, boolean registration) throws IOException {
        database.write(connection -> {
            if (registration) {
                SqliteDatabase.execute(connection, """
                        INSERT INTO accounts (user_id, nickname, salt, hash, avatar, created_at, profile_revision)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, a.userId(), a.nickname(), a.salt(), a.hash(), a.avatar(), a.createdAt(), a.profileRevision());
            } else if (SqliteDatabase.execute(connection, """
                    UPDATE accounts SET nickname = ?, salt = ?, hash = ?, avatar = ?, profile_revision = ? WHERE user_id = ?
                    """, a.nickname(), a.salt(), a.hash(), a.avatar(), a.profileRevision(), a.userId()) != 1) {
                throw new IOException("账号记录不存在");
            }
            return null;
        });
        accounts.put(a.userId(), a);
    }
    public synchronized UserProfile profile(String id) throws ProtocolException {
        Account a = accounts.get(id);
        if (a == null) throw new ProtocolException("NOT_AUTHENTICATED", "账号不存在");
        return new UserProfile(a.userId(), a.nickname(), Objects.toString(a.avatar(), ""), a.createdAt());
    }
    synchronized String nickname(String id) {
        Account account = accounts.get(id);
        return account == null ? null : account.nickname();
    }
    synchronized List<SocialItem> findUsers(String user, String query) throws ProtocolException {
        String term = query.strip();
        if (term.isEmpty()) throw new ProtocolException("MISSING_FIELD", "请输入昵称或用户名");
        if (term.length() > 40) throw new ProtocolException("BAD_MESSAGE", "用户搜索最多 40 个字符");
        String keyword = term.toLowerCase(Locale.ROOT);
        return accounts.values().stream()
                .filter(account -> !account.userId().equals(user))
                .filter(account -> account.userId().toLowerCase(Locale.ROOT).contains(keyword)
                        || account.nickname().toLowerCase(Locale.ROOT).contains(keyword))
                .map(account -> SocialItem.user(SocialItem.Kind.USER, account.userId(), account.nickname()))
                .toList();
    }
    synchronized DisplayProfile displayProfile(String id) {
        Account account = accounts.get(id);
        return account == null ? null : new DisplayProfile(account.nickname(), Objects.toString(account.avatar(), ""), account.profileRevision());
    }
    void changePassword(String id, String current, String next) throws IOException {
        validatePassword(current);
        validatePassword(next);
        Account account = lookup(id);
        if (account == null) throw new ProtocolException("NOT_AUTHENTICATED", "账号不存在");
        byte[] previousSalt = Base64.getDecoder().decode(account.salt());
        if (!MessageDigest.isEqual(hash(current, previousSalt), Base64.getDecoder().decode(account.hash()))) {
            throw new ProtocolException("WRONG_PASSWORD", "当前密码不正确");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        var base64 = Base64.getEncoder();
        String digest = base64.encodeToString(hash(next, salt));
        synchronized (this) {
            Account latest = accounts.get(id);
            if (!sameCredentials(account, latest)) throw new ProtocolException("WRONG_PASSWORD", "当前密码不正确");
            save(new Account(id, latest.nickname(), base64.encodeToString(salt), digest,
                    latest.avatar(), latest.createdAt(), latest.profileRevision()), false);
        }
    }
    public synchronized UserProfile updateProfile(String id, String nickname, String avatar) throws IOException {
        if (nickname == null || nickname.isBlank()) throw new ProtocolException("MISSING_FIELD", "请填写昵称");
        if (nickname.strip().length() > 40) throw new ProtocolException("INVALID_NICKNAME", "昵称最多 40 字符");
        if (avatar == null) throw new ProtocolException("MISSING_FIELD", "缺少头像设置");
        AvatarData.decode(avatar);
        Account a = accounts.get(id);
        if (a == null) throw new ProtocolException("NOT_AUTHENTICATED", "账号不存在");
        save(new Account(id, nickname.strip(), a.salt(), a.hash(), avatar, a.createdAt(), a.profileRevision() + 1), false);
        return profile(id);
    }
    public String authenticate(String id, String password) throws IOException {
        if (id == null || id.isBlank() || password == null || password.isEmpty())
            throw new ProtocolException("MISSING_FIELD", "请填写用户名和密码");
        id = Validation.userId(id);
        validatePassword(password);
        Account a = lookup(id);
        byte[] salt = a == null ? new byte[16] : Base64.getDecoder().decode(a.salt());
        byte[] actual = hash(password, salt);
        if (a == null || !MessageDigest.isEqual(actual, Base64.getDecoder().decode(a.hash())) || !sameCredentials(a, lookup(id)))
            throw new ProtocolException("LOGIN_FAILED", "用户名或密码错误");
        return id;
    }
    private synchronized Account lookup(String id) { return accounts.get(id); }
    private static boolean sameCredentials(Account first, Account second) {
        return second != null && first.salt().equals(second.salt()) && first.hash().equals(second.hash());
    }
    public static void validatePassword(String password) throws ProtocolException {
        if (password == null || password.isEmpty()) throw new ProtocolException("MISSING_FIELD", "请填写密码");
        if (password.length() < 6 || password.length() > 128)
            throw new ProtocolException("INVALID_PASSWORD", "密码长度须为 6～128 字符");
    }
    private byte[] hash(String password, byte[] salt) throws IOException {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException ex) { throw new IOException("密码处理失败", ex); }
        finally { spec.clearPassword(); }
    }
}
