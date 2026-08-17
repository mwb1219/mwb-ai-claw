package com.mwb.ai.claw.infrastructure.collaboration;

import com.mwb.ai.claw.domain.collaboration.OrchestrationDefinition;
import com.mwb.ai.claw.domain.collaboration.OrchestrationSelector;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规则编排选择器：按编排 keywords 命中次数匹配用户意图。
 * <p>
 * 与 RuleBasedAgentRouter 同思路（忽略大小写的包含匹配），但目标从「选 Agent」
 * 升级为「选编排」。多编排命中时取命中次数最多的；全部未命中返回 null（由调用方回退默认编排）。
 */
@Component
public class RuleBasedOrchestrationSelector implements OrchestrationSelector {

    @Override
    public String select(String message, List<OrchestrationDefinition> definitions) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }
        String lowerMessage = message.toLowerCase();
        String bestId = null;
        int bestHits = 0;
        for (OrchestrationDefinition definition : definitions) {
            if (definition.getKeywords() == null || definition.getKeywords().isEmpty()) {
                continue;
            }
            int hits = 0;
            for (String keyword : definition.getKeywords()) {
                if (keyword != null && !keyword.trim().isEmpty()
                        && lowerMessage.contains(keyword.trim().toLowerCase())) {
                    hits++;
                }
            }
            if (hits > bestHits) {
                bestHits = hits;
                bestId = definition.getId();
            }
        }
        return bestId;
    }
}
