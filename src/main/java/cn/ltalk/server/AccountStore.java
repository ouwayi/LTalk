package cn.ltalk.server;

import cn.ltalk.common.*;
import com.google.gson.Gson;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One server owns the account directory. Passwords never enter persisted JSON. */
public final class AccountStore {
    private record Account(String userId, String nickname, String salt, String hash, String avatar, String createdAt, long profileRevision) { }
    record DisplayProfile(String nickname, String avatar, long revision) { }
    private final Path directory;
    private final Map<String, Account> accounts = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Gson gson = new Gson();
    public AccountStore(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                try {
                    Account a = gson.fromJson(Files.readString(file), Account.class);
                    Validation.userId(a.userId());
                    if (a.nickname() == null || Base64.getDecoder().decode(a.salt()).length != 16
                            || Base64.getDecoder().decode(a.hash()).length != 32) throw new IOException("账号文件损坏");
                    AvatarData.decode(a.avatar());
                    accounts.put(a.userId(), a);
                } catch (RuntimeException ex) { throw new IOException("账号文件损坏，拒绝覆盖", ex); }
            }
        }
    }
    public synchronized String register(String id, String nickname, String password, boolean generated) throws IOException {
        if (nickname == null || nickname.isBlank()) throw new ProtocolException("MISSING_FIELD", "请填写昵称");
        if (nickname.strip().length() > 40) throw new ProtocolException("INVALID_NICKNAME", "昵称最多 40 字符");
        validatePassword(password);
        if (generated) {
            do { id = "u_" + HexFormat.of().formatHex(random.generateSeed(8)); } while (accounts.containsKey(id));
        } else {
            if (id == null || id.isBlank()) throw new ProtocolException("MISSING_FIELD", "请填写用户名");
            id = Validation.userId(id);
        }
        if (accounts.containsKey(id)) throw new ProtocolException("USER_EXISTS", "用户名已注册");
        byte[] salt = new byte[16]; random.nextBytes(salt);
        Account a = new Account(id, nickname.strip(), Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash(password, salt)), "", java.time.Instant.now().toString(), 0);
        save(a);
        return id;
    }
    private void save(Account a) throws IOException {
        Path temporary = Files.createTempFile(directory, ".account-", ".tmp");
        try {
            Files.writeString(temporary, gson.toJson(a));
            Files.move(temporary, directory.resolve(fileKey(a.userId()) + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            accounts.put(a.userId(), a);
        } finally { Files.deleteIfExists(temporary); }
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
    synchronized DisplayProfile displayProfile(String id) {
        Account account = accounts.get(id);
        return account == null ? null : new DisplayProfile(account.nickname(), Objects.toString(account.avatar(), ""), account.profileRevision());
    }
    public synchronized UserProfile updateProfile(String id, String nickname, String avatar) throws IOException {
        if (nickname == null || nickname.isBlank()) throw new ProtocolException("MISSING_FIELD", "请填写昵称");
        if (nickname.strip().length() > 40) throw new ProtocolException("INVALID_NICKNAME", "昵称最多 40 字符");
        if (avatar == null) throw new ProtocolException("MISSING_FIELD", "缺少头像设置");
        AvatarData.decode(avatar);
        Account a = accounts.get(id);
        if (a == null) throw new ProtocolException("NOT_AUTHENTICATED", "账号不存在");
        save(new Account(id, nickname.strip(), a.salt(), a.hash(), avatar, a.createdAt(), a.profileRevision() + 1));
        return profile(id);
    }
    public synchronized String authenticate(String id, String password) throws IOException {
        if (id == null || id.isBlank() || password == null || password.isEmpty())
            throw new ProtocolException("MISSING_FIELD", "请填写用户名和密码");
        id = Validation.userId(id);
        validatePassword(password);
        Account a = accounts.get(id);
        byte[] salt = a == null ? new byte[16] : Base64.getDecoder().decode(a.salt());
        byte[] actual = hash(password, salt);
        if (a == null || !MessageDigest.isEqual(actual, Base64.getDecoder().decode(a.hash())))
            throw new ProtocolException("LOGIN_FAILED", "用户名或密码错误");
        return id;
    }
    static String fileKey(String user) { return HexFormat.of().formatHex(user.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
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
