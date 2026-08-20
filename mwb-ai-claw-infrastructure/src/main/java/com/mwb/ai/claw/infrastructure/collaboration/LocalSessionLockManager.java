package com.mwb.ai.claw.infrastructure.collaboration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.mwb.ai.claw.domain.scope.AgentScope;

/**
 * 本地会话锁实现（默认，单实例部署）：
 * {@link ConcurrentHashMap} 持有锁实例，引用计数归零即清理，避免锁表无限增长。
 * 锁 key = scope.keyPrefix() + ":" + sessionId；同会话串行，不同会话 / 用户完全并行。
 */
public class LocalSessionLockManager implements SessionLockManager {

    /** 锁表项：ReentrantLock + 当前持有者计数（计数归零后从表移除） */
    private static final class LockEntry {
        final ReentrantLock lock = new ReentrantLock();
        int refs;
    }

    private final ConcurrentHashMap<String, LockEntry> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T executeWithLock(AgentScope scope, String sessionId, Supplier<T> task) {
        String key = lockKey(scope, sessionId);
        ReentrantLock lock = acquire(key);
        try {
            return task.get();
        } finally {
            release(key, lock);
        }
    }

    @Override
    public void executeWithLock(AgentScope scope, String sessionId, Runnable task) {
        String key = lockKey(scope, sessionId);
        ReentrantLock lock = acquire(key);
        try {
            task.run();
        } finally {
            release(key, lock);
        }
    }

    private ReentrantLock acquire(String key) {
        LockEntry entry = locks.compute(key, (k, v) -> {
            LockEntry e = v == null ? new LockEntry() : v;
            e.refs++;
            return e;
        });
        ReentrantLock lock = entry.lock;
        lock.lock();
        return lock;
    }

    private void release(String key, ReentrantLock lock) {
        lock.unlock();
        locks.computeIfPresent(key, (k, v) -> {
            v.refs--;
            return v.refs <= 0 ? null : v;
        });
    }

    private String lockKey(AgentScope scope, String sessionId) {
        AgentScope s = scope != null ? scope : AgentScope.defaultScope();
        String sid = sessionId == null ? "" : sessionId;
        return s.keyPrefix() + ":" + sid;
    }
}
