package com.mwb.ai.claw.example.web.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 密码哈希工具：SHA-256 + 随机盐，存储格式 {@code saltHex:hashHex}。
 * <p>
 * 不引入额外依赖，仅用于示例工程的用户注册 / 登录密码校验；生产环境建议替换为 BCrypt 等慢哈希。
 */
public final class PasswordUtils {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtils() {
    }

    /** 生成密码哈希（随机盐 + SHA-256），返回 {@code salt:hash} */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] digest = sha256(salt, password.getBytes(StandardCharsets.UTF_8));
        return hex(salt) + ":" + hex(digest);
    }

    /** 校验明文密码是否匹配存储的哈希 */
    public static boolean verify(String password, String stored) {
        if (stored == null || !stored.contains(":")) {
            return false;
        }
        String[] parts = stored.split(":", 2);
        byte[] salt = hexToBytes(parts[0]);
        byte[] expected = hexToBytes(parts[1]);
        byte[] actual = sha256(salt, password.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] sha256(byte[] salt, byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            return md.digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
