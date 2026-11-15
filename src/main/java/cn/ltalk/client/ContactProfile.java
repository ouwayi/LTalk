package cn.ltalk.client;

import java.awt.image.BufferedImage;

/** The display data for one account. The reader decodes the image before handing it to the UI. */
public record ContactProfile(String userId, String nickname, BufferedImage avatar, long revision) {
    public String displayName() { return displayName(userId, nickname); }

    public static String displayName(String userId, String nickname) {
        return nickname == null || nickname.isBlank() ? userId : nickname.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
