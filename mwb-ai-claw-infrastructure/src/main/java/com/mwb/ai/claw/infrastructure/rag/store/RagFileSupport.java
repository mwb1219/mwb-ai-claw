package com.mwb.ai.claw.infrastructure.rag.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 本地 RAG 存储共享的路径校验与原子写入工具。
 */
public final class RagFileSupport {

    /** 路径分隔符 / 文件系统保留字符：任何场景都禁止出现在 ID 中（防路径穿越 + 跨平台文件名安全）。 */
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[/\\\\:*?\"<>|]");

    /** ID 最大长度（字符）。 */
    private static final int MAX_ID_LENGTH = 128;

    private RagFileSupport() {
    }

    /**
     * 校验知识库 / 文档 ID。ID 会作为本地存储目录或文件名，因此：
     * <ul>
     *   <li>允许任意 Unicode（含中文），便于中文场景直接使用业务名；</li>
     *   <li>拒绝路径分隔符与文件系统保留字符（{@code / \ : * ? " < > |}）、控制字符；</li>
     *   <li>拒绝包含连续点号 {@code ..}（纵深防御路径穿越）；</li>
     *   <li>长度 1-128 字符。</li>
     * </ul>
     */
    public static String requireId(String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(name + " 长度不能超过 " + MAX_ID_LENGTH + " 个字符");
        }
        if (FORBIDDEN_CHARS.matcher(value).find() || containsControl(value) || value.contains("..")) {
            throw new IllegalArgumentException(
                    name + " 不能包含路径分隔符/保留字符（/ \\ : * ? \" < > |）、控制字符或连续点号（..）");
        }
        return value;
    }

    /** 是否包含控制字符（0x00-0x1F、0x7F）。 */
    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    public static void atomicWrite(Path target, String content) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入 RAG 文件失败: " + target, e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // 临时文件清理由下一次运维处理，不覆盖原始异常。
            }
        }
    }
}
