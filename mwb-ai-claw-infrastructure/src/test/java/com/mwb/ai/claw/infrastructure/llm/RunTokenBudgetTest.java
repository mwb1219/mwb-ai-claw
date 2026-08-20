package com.mwb.ai.claw.infrastructure.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * RunTokenBudget 单元测试（C2 韧性 / C3 预算保护）：
 * 预算累计、超限拒绝、不限预算、ThreadLocal bind/unbind。
 */
public class RunTokenBudgetTest {

    @Test
    public void testTryConsume_withinLimit() {
        RunTokenBudget budget = new RunTokenBudget(100);
        assertTrue(budget.tryConsume(60));
        assertTrue(budget.tryConsume(40));
        assertEquals(100, budget.getConsumed());
    }

    @Test
    public void testTryConsume_exceedsLimit_noSideEffect() {
        RunTokenBudget budget = new RunTokenBudget(100);
        assertTrue(budget.tryConsume(60));
        // 超限：拒绝且不改变已消耗
        assertFalse(budget.tryConsume(50));
        assertEquals(60, budget.getConsumed());
    }

    @Test
    public void testUnlimited() {
        RunTokenBudget budget = new RunTokenBudget(0);
        assertTrue(budget.isUnlimited());
        assertTrue(budget.tryConsume(Long.MAX_VALUE));
    }

    @Test
    public void testNegativeLimitMeansUnlimited() {
        RunTokenBudget budget = new RunTokenBudget(-5);
        assertTrue(budget.isUnlimited());
    }

    @Test
    public void testNonPositiveTokensAlwaysAccepted() {
        RunTokenBudget budget = new RunTokenBudget(10);
        assertTrue(budget.tryConsume(0));
        assertTrue(budget.tryConsume(-3));
        assertEquals(0, budget.getConsumed());
    }

    @Test
    public void testBindCurrentUnbind() {
        assertNull(RunTokenBudget.current());
        RunTokenBudget budget = RunTokenBudget.bind(50);
        assertEquals(budget, RunTokenBudget.current());
        RunTokenBudget.unbind();
        assertNull(RunTokenBudget.current());
    }
}
