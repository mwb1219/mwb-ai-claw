package com.mwb.ai.claw.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 流式任务注册表：维护 会话 ID → 执行任务 的映射，用于断连 / 超时 / 错误时的任务回收。
 * <p>
 * SSE / WebSocket 的 ReAct 执行提交到线程池后在此登记，客户端断连时
 * {@link #cancel} 触发 {@link Future#cancel(boolean)}（中断执行线程）：
 * - 流式 LLM（HttpURLConnection）可中断，readLine 抛异常后按 Premature EOF 保留已输出部分；
 * - 同步 LLM（RestTemplate）不可直接中断，取消后最坏等待 read-timeout-ms 线程退出。
 * 任务正常结束由执行线程主动 {@link #unregister}，避免映射泄漏。
 */
@Component
public class StreamTaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(StreamTaskRegistry.class);

    private final Map<String, Future<?>> tasks = new ConcurrentHashMap<>();

    /**
     * 登记会话任务。sessionId 为空时不登记（无法精确回收，交由中断 + 读超时兜底）。
     */
    public void register(String sessionId, Future<?> task) {
        if (sessionId == null || task == null) {
            return;
        }
        tasks.put(sessionId, task);
    }

    /**
     * 会话任务正常结束（或已取消）后移除登记。
     */
    public void unregister(String sessionId, Future<?> task) {
        if (sessionId == null) {
            return;
        }
        if (task == null) {
            tasks.remove(sessionId);
        } else {
            tasks.remove(sessionId, task);
        }
    }

    /**
     * 断连 / 超时 / 错误回收：取消该会话仍在执行的流式任务（中断执行线程）。
     */
    public void cancel(String sessionId) {
        if (sessionId == null) {
            return;
        }
        Future<?> task = tasks.remove(sessionId);
        if (task != null) {
            log.info("回收流式任务: sessionId={}, cancelInterrupted={}", sessionId, task.cancel(true));
        }
    }

    /**
     * 当前活跃的流式任务数（监控 / 测试用）。
     */
    public int activeCount() {
        return tasks.size();
    }
}
