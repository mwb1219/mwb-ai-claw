package com.mwb.ai.claw.infrastructure.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.domain.core.ProgressCallback;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import com.mwb.ai.claw.domain.tool.ToolPermissionChecker;
import com.mwb.ai.claw.domain.tool.ToolResult;
import com.mwb.ai.claw.domain.tool.ToolSpec;
import com.mwb.ai.claw.exception.BizException;

/**
 * ToolGatewayImpl 工具超时兜底与异常分类测试（C3）：
 * 统一 Future 包装超时 → 取消 → 明确错误；BizException（业务）与其他异常分类；
 * 权限拒绝与工具不存在不中断 ReAct（返回 ToolResult.error）。
 */
public class ToolGatewayTimeoutTest {

    private ToolGatewayImpl gateway;

    @Before
    public void setUp() {
        gateway = new ToolGatewayImpl(Collections.<ToolExecutor>emptyList(), null, null, 1, 10000);
    }

    // ---------- 工具超时兜底 ----------

    @Test
    public void testTimeout_returnsErrorAndCancels() throws Exception {
        gateway.registerExecutor(slowExecutor("slow-tool", 3000));
        long start = System.currentTimeMillis();
        ToolResult result = gateway.execute("slow-tool", "{}");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(result.isSuccess() == false);
        assertTrue(result.getError().contains("工具执行超时(1s)"));
        // 应在 1s 超时附近返回，而非等待 executor 3s 跑完
        assertTrue("超时兜底应在配置超时附近返回，实际耗时=" + elapsed + "ms", elapsed < 2500);
    }

    @Test
    public void testNoTimeout_normalSuccess() {
        gateway.registerExecutor(okExecutor("fast-tool", "ok-result"));
        ToolResult result = gateway.execute("fast-tool", "{}");
        assertTrue(result.isSuccess());
        assertEquals("ok-result", result.getOutput());
    }

    // ---------- 异常分类 ----------

    @Test
    public void testBizException_mappedAsBusinessError() {
        gateway.registerExecutor(boomExecutor("biz-tool", new BizException("B_XXX", "业务校验失败")));
        ToolResult result = gateway.execute("biz-tool", "{}");
        assertTrue(!result.isSuccess());
        assertTrue("业务异常应映射为「工具执行失败」，实际=" + result.getError(),
                result.getError().contains("工具执行失败") && result.getError().contains("业务校验失败"));
    }

    @Test
    public void testRuntimeException_mappedAsExecutionError() {
        gateway.registerExecutor(boomExecutor("boom-tool", new IllegalStateException("内部异常")));
        ToolResult result = gateway.execute("boom-tool", "{}");
        assertTrue(!result.isSuccess());
        assertTrue("其他异常应映射为「工具执行异常」，实际=" + result.getError(),
                result.getError().contains("工具执行异常"));
    }

    @Test
    public void testInterruptedException_returnsInterruptedError() throws Exception {
        Thread t = new Thread(() -> {
            gateway.registerExecutor(slowExecutor("interrupt-tool", 5000));
            Thread.currentThread().interrupt();
            ToolResult result = gateway.execute("interrupt-tool", "{}");
            assertTrue(!result.isSuccess());
        });
        t.start();
        t.join(5000);
        assertTrue(t.isAlive() == false);
    }

    // ---------- 权限与存在性 ----------

    @Test
    public void testPermissionDenied_rejectedWithoutExecute() {
        ToolGatewayImpl g = new ToolGatewayImpl(Collections.<ToolExecutor>emptyList(), deniedChecker(), null, 1, 10000);
        g.registerExecutor(okExecutor("secret-tool", "should-not-run"));
        ToolResult result = g.execute("secret-tool", "{}");
        assertTrue(!result.isSuccess());
        assertTrue(result.getError().contains("无权限"));
    }

    @Test
    public void testToolNotFound_returnsError() {
        ToolResult result = gateway.execute("not-exist", "{}");
        assertTrue(!result.isSuccess());
        assertTrue(result.getError().contains("工具不存在"));
    }

    // ---------- fake 实现 ----------

    private ToolExecutor slowExecutor(String name, long sleepMs) {
        return new FakeTool(name) {
            @Override
            public ToolResult execute(String argumentsJson) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ToolResult.error("interrupted");
                }
                return ToolResult.success("slow-done");
            }
        };
    }

    private ToolExecutor okExecutor(String name, String output) {
        return new FakeTool(name) {
            @Override
            public ToolResult execute(String argumentsJson) {
                return ToolResult.success(output);
            }
        };
    }

    private ToolExecutor boomExecutor(String name, RuntimeException ex) {
        return new FakeTool(name) {
            @Override
            public ToolResult execute(String argumentsJson) {
                throw ex;
            }
        };
    }

    private ToolPermissionChecker deniedChecker() {
        return (scope, toolName) -> false;
    }

    /** 最小 ToolExecutor 假实现 */
    private abstract static class FakeTool implements ToolExecutor {
        private final String name;

        FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public ToolSpec getSpec() {
            return new ToolSpec(name, "fake", "{}");
        }
    }
}
