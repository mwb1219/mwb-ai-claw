package com.mwb.ai.claw.infrastructure.collaboration.routing;

import com.mwb.ai.claw.domain.core.Agent;
import com.mwb.ai.claw.domain.core.AgentGateway;
import com.mwb.ai.claw.infrastructure.collaboration.routing.RuleBasedAgentRouter;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 规则路由单元测试：验证关键词匹配、未命中回退、大小写不敏感等场景。
 * <p>
 * RuleBasedAgentRouter 为 domain 纯领域类，通过构造函数注入 AgentGateway，此处用 fake 实现测试。
 */
public class RuleBasedAgentRouterTest {

    private RuleBasedAgentRouter router;

    @Before
    public void setUp() {
        router = new RuleBasedAgentRouter(buildFakeAgentGateway());
    }

    @Test
    public void testRoute_hitKeyword() {
        // 命中 coder 关键词
        assertEquals("coder", router.route("帮我修复一个登录 bug"));
        assertEquals("coder", router.route("这段代码怎么实现"));
    }

    @Test
    public void testRoute_hitAnotherKeyword() {
        assertEquals("researcher", router.route("帮我搜索一下相关资料"));
    }

    @Test
    public void testRoute_caseInsensitive() {
        // bug 关键词大小写不敏感
        assertEquals("coder", router.route("这里有个 BUG 需要修复"));
    }

    @Test
    public void testRoute_noMatch_returnsNull() {
        assertNull(router.route("今天天气怎么样"));
    }

    @Test
    public void testRoute_emptyMessage_returnsNull() {
        assertNull(router.route(null));
        assertNull(router.route(""));
        assertNull(router.route("   "));
    }

    // ==================== 辅助 ====================

    /** 构造 fake AgentGateway，返回两个带关键词的专家 Agent */
    private AgentGateway buildFakeAgentGateway() {
        return new AgentGateway() {
            @Override
            public Agent getAgent(String agentId) {
                return null;
            }

            @Override
            public List<Agent> listAgents() {
                List<Agent> agents = new ArrayList<>();

                Agent coder = new Agent();
                coder.setAgentId("coder");
                coder.setKeywords(new ArrayList<>(Arrays.asList("代码", "bug", "实现", "调试", "编译")));
                agents.add(coder);

                Agent researcher = new Agent();
                researcher.setAgentId("researcher");
                researcher.setKeywords(new ArrayList<>(Arrays.asList("搜索", "查询", "资料", "调研")));
                agents.add(researcher);

                // 默认 Agent 无关键词
                Agent general = new Agent();
                general.setAgentId("default");
                general.setKeywords(new ArrayList<>());
                agents.add(general);

                return agents;
            }
        };
    }
}
