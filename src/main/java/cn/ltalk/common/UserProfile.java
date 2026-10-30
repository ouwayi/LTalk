package cn.ltalk.common;

/** Public-to-self account data; never contains password material. */
public record UserProfile(String userId, String nickname, String avatar, String createdAt) {
    public static UserProfile from(Message message) {
        return new UserProfile(message.userId(), message.nickname(), message.avatar(), message.createdAt());
    }
}
