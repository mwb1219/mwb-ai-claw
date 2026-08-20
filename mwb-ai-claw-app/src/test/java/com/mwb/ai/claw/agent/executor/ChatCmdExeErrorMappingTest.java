package com.mwb.ai.claw.agent.executor;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;

import org.junit.Test;

import com.mwb.ai.claw.domain.core.ErrorCategory;

/**
 * ChatCmdExe.mapErrorCode 错误码映射测试（C3）：
 * 错误分类 + 错误信息 → 统一错误码（BUDGET_EXCEEDED / LLM_TIMEOUT / RATE_LIMITED /
 * LLM_UNAVAILABLE / SYSTEM_ERROR）。
 */
public class ChatCmdExeErrorMappingTest {

    private final ChatCmdExe exe = new ChatCmdExe();

    private String map(ErrorCategory category, String message) {
        try {
            Method m = ChatCmdExe.class.getDeclaredMethod("mapErrorCode",
                    com.mwb.ai.claw.domain.core.ErrorCategory.class, String.class);
            m.setAccessible(true);
            return (String) m.invoke(exe, category, message);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testBudget_mapsToBudgetExceeded() {
        assertEquals("BUDGET_EXCEEDED", map(ErrorCategory.BUDGET, "预算超限"));
    }

    @Test
    public void testTransientTimeout_mapsToLlmTimeout() {
        assertEquals("LLM_TIMEOUT", map(ErrorCategory.TRANSIENT, "LLM 调用超时: read timeout"));
    }

    @Test
    public void testTransientRateLimit_mapsToRateLimited() {
        assertEquals("RATE_LIMITED", map(ErrorCategory.TRANSIENT, "HTTP 429: rate limited"));
    }

    @Test
    public void testTransientOther_mapsToLlmUnavailable() {
        assertEquals("LLM_UNAVAILABLE", map(ErrorCategory.TRANSIENT, "HTTP 500: server error"));
    }

    @Test
    public void testBusiness_mapsToSystemError() {
        assertEquals("SYSTEM_ERROR", map(ErrorCategory.BUSINESS, "业务错误"));
    }

    @Test
    public void testNullCategory_mapsToSystemError() {
        assertEquals("SYSTEM_ERROR", map(null, "未知错误"));
    }
}
