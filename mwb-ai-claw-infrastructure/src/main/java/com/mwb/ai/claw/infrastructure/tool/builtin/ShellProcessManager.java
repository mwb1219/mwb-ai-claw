package com.mwb.ai.claw.infrastructure.tool.builtin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Shell 后台进程管理器：持有仍在运行（或超时转后台）的 shell 进程，持续读取输出，
 * 供 {@code shell_status} 工具查询状态 / 输出 / 终止。
 * <p>
 * 每个任务由守护线程异步读取 stdout，输出持续累积；任务数超过上限时优先清理已完成任务。
 */
@Component
public class ShellProcessManager {

    private static final Logger log = LoggerFactory.getLogger(ShellProcessManager.class);

    /** 后台任务保留上限，超出时优先淘汰已完成任务 */
    private static final int MAX_TASKS = 50;

    private final Map<String, ShellTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();

    /**
     * 注册一个 shell 进程为后台任务，并立即启动输出读取线程。
     *
     * @param process 已启动的进程
     * @param onLine  输出行回调（可为 null），用于实时流式回显（如 "[Stream] line"）
     * @return 任务句柄
     */
    public ShellTask register(Process process, Consumer<String> onLine) {
        evictIfNeeded();
        ShellTask task = new ShellTask(String.valueOf(idSeq.incrementAndGet()), process, onLine);
        tasks.put(task.getId(), task);
        task.start();
        log.info("Shell 后台任务已注册: taskId={}", task.getId());
        return task;
    }

    public ShellTask get(String taskId) {
        return tasks.get(taskId);
    }

    public boolean remove(String taskId) {
        return tasks.remove(taskId) != null;
    }

    public int size() {
        return tasks.size();
    }

    /** 超过上限时优先清理一个已完成任务（无已完成则保留最旧，继续累积由后续执行兜底） */
    private void evictIfNeeded() {
        if (tasks.size() < MAX_TASKS) {
            return;
        }
        for (Map.Entry<String, ShellTask> entry : tasks.entrySet()) {
            if (entry.getValue().isDone()) {
                tasks.remove(entry.getKey());
                log.info("清理已完成的后台任务: taskId={}", entry.getKey());
                return;
            }
        }
    }

    /**
     * 单个后台任务：进程 + 输出缓冲 + 读取线程。
     */
    public static class ShellTask {

        private final String id;
        private final Process process;
        private final Consumer<String> onLine;
        private final StringBuffer output = new StringBuffer();
        private final AtomicBoolean done = new AtomicBoolean(false);
        private final AtomicInteger exitCode = new AtomicInteger(-1);
        private final long startTime = System.currentTimeMillis();
        private volatile Thread readerThread;

        ShellTask(String id, Process process, Consumer<String> onLine) {
            this.id = id;
            this.process = process;
            this.onLine = onLine;
        }

        void start() {
            readerThread = new Thread(this::readLoop, "shell-task-" + id);
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                    if (onLine != null) {
                        try {
                            onLine.accept(line);
                        } catch (Exception ignore) {
                            // 回调异常（如终端已关闭）不影响任务继续
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("读取 shell 任务输出异常: taskId={}", id, e);
            } finally {
                done.set(true);
                try {
                    exitCode.set(process.exitValue());
                } catch (IllegalThreadStateException e) {
                    exitCode.set(-1);
                }
            }
        }

        public String getId() {
            return id;
        }

        public boolean isDone() {
            return done.get();
        }

        public int getExitCode() {
            return exitCode.get();
        }

        public long getStartTime() {
            return startTime;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public String getOutput() {
            synchronized (output) {
                return output.toString();
            }
        }

        /** 等待读取线程把剩余输出刷完（进程已结束但缓冲未读完时使用） */
        public void joinReader(long millis) {
            Thread t = readerThread;
            if (t != null) {
                try {
                    t.join(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public void kill() {
            try {
                process.destroyForcibly();
                log.info("已终止 shell 后台任务: taskId={}", id);
            } catch (Exception e) {
                log.warn("终止 shell 后台任务异常: taskId={}", id, e);
            }
        }
    }
}
