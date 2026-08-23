package com.mwb.ai.claw.infrastructure.rag;

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
final class RagFileSupport {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private RagFileSupport() {
    }

    static String requireId(String name, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(name + " 仅允许 1-128 位字母、数字、点、下划线和连字符");
        }
        return value;
    }

    static void atomicWrite(Path target, String content) {
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
