package com.mwb.ai.claw.infrastructure.memory;

import java.util.List;

import com.mwb.ai.claw.domain.core.Message;
import com.mwb.ai.claw.domain.core.MessageRole;

/**
 * 消息重要度启发式估算（0-1）：用于重要度驱动的换页策略。
 * <p>
 * 不调用 LLM（每轮评估成本高），采用轻量启发式：
 * 基础 0.4 + 用户消息加成 + 重要关键词加成 + 信息量（长度）加成。
 */
public class MessageImportanceEstimator {

    /** 触发重要度加成的关键词 */
    private static final String[] IMPORTANT_KEYWORDS = {
            "记住", "记得", "偏好", "喜欢", "不喜欢", "重要", "必须", "禁止", "不要",
            "决定", "约束", "项目", "用户", "我叫", "我是", "密码", "邮箱", "地址",
            "目标", "计划", "方案", "结论", "约定"
    };

    private MessageImportanceEstimator() {
    }

    /**
     * 估算单条消息重要度。
     */
    public static double estimate(Message message) {
        if (message == null || message.getContent() == null || message.getContent().isEmpty()) {
            return 0.1;
        }
        double score = 0.4;
        if (message.getRole() == MessageRole.USER) {
            score += 0.15;
        } else if (message.getRole() == MessageRole.TOOL) {
            score -= 0.1;
        }
        String content = message.getContent();
        for (String kw : IMPORTANT_KEYWORDS) {
            if (content.contains(kw)) {
                score += 0.2;
                break;
            }
        }
        if (content.length() > 80) {
            score += 0.1;
        }
        return Math.max(0.05, Math.min(0.95, score));
    }

    /**
     * 块价值：以块内 user 消息的最高重要度为代表（系统生成的空 assistant / tool 结果消息
     * 价值低，不应拉低用户高价值话题的保留优先级）。
     */
    public static double maxUserImportance(List<Message> messages) {
        double max = 0;
        for (Message m : messages) {
            if (m.getRole() == MessageRole.USER) {
                max = Math.max(max, estimate(m));
            }
        }
        return max;
    }
}
