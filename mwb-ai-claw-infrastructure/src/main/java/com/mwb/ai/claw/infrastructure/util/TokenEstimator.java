package com.mwb.ai.claw.infrastructure.util;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.memory.MemoryPage;

import java.util.List;

/**
 * Token 估算工具：中文场景下近似 1 token ≈ 1 字符，英文场景 4 字符 ≈ 1 token。
 * <p>
 * 不引入真实 tokenizer，仅用于记忆预算的粗粒度分配，足够工程使用。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int length = text.length();
        // 中文（CJK）字符按 1 token 计，其余字符按 4 字符 1 token 计
        int cjk = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            }
        }
        int other = length - cjk;
        return cjk + (other + 3) / 4;
    }

    public static int estimate(Message message) {
        int tokens = estimate(message.getContent());
        if (message.getToolCalls() != null) {
            tokens += message.getToolCalls().size() * 8;
        }
        return tokens;
    }

    public static int estimate(List<Message> messages) {
        int total = 0;
        for (Message message : messages) {
            total += estimate(message);
        }
        return total;
    }

    public static int estimate(MemoryPage page) {
        return estimate(page.getContent());
    }
}
