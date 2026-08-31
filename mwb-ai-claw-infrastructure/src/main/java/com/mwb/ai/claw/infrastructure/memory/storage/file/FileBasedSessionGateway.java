package com.mwb.ai.claw.infrastructure.memory.storage.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;

import com.mwb.ai.claw.infrastructure.memory.storage.memory.MemorySessionGatewayImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.core.SessionGateway;
import com.mwb.ai.claw.domain.scope.AgentScope;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.domain.util.JsonUtils;

/**
 * 文件持久化版会话存储：每个会话保存为 .agent/sessions/[tenant/user/]<sessionId>.json。
 * <p>
 * 内存缓存 + 文件持久化，实现跨重启的会话隔离与持久化。
 * 目录按租户/用户维度隔离：scope 化后会话文件位于 &lt;namespace&gt;/&lt;sessionId&gt;.json，
 * 未启用多租户（legacy 模式）时仍位于 sessions/&lt;sessionId&gt;.json，保持向后兼容。
 * 替代原先纯内存的 {@link MemorySessionGatewayImpl}。
 */
public class FileBasedSessionGateway implements SessionGateway {

    private static final Logger log = LoggerFactory.getLogger(FileBasedSessionGateway.class);

    private final Path sessionsDir;
    private final ConcurrentMap<String, Session> cache = new ConcurrentHashMap<>();

    public FileBasedSessionGateway(AgentProperties properties) {
        String dir = properties.getMemoryDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent";
        }
        this.sessionsDir = Paths.get(dir).resolve("sessions");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(sessionsDir);
            // 启动时加载所有已有会话到缓存（递归子目录以支持多租户布局）
            try (Stream<Path> files = Files.walk(sessionsDir)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".json"))
                        .forEach(this::loadSessionFile);
            }
            log.info("会话存储已初始化: 目录={}, 已加载 {} 个会话",
                    sessionsDir.toAbsolutePath(), cache.size());
        } catch (IOException e) {
            log.error("初始化会话存储失败", e);
        }
    }

    private void loadSessionFile(Path p) {
        try {
            String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            Session session = JsonUtils.fromJson(json, Session.class);
            cache.put(cacheKey(session.getTenantId(), session.getUserId(), session.getSessionId()), session);
        } catch (Exception e) {
            log.warn("加载会话文件失败: {} -> {}", p.getFileName(), e.getMessage());
        }
    }

    // ==================== MemoryGateway 实现 ====================

    private static String cacheKey(String tenantId, String userId, String sessionId) {
        return AgentScope.of(tenantId, userId).keyPrefix() + ":" + sessionId;
    }

    @Override
    public void saveSession(Session session) {
        cache.put(cacheKey(session.getTenantId(), session.getUserId(), session.getSessionId()), session);
        Path file = sessionFile(AgentScope.of(session.getTenantId(), session.getUserId()), session.getSessionId());
        try {
            Files.createDirectories(file.getParent());
            String json = JsonUtils.toJson(session);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            log.debug("会话已持久化: {} ({} bytes)", session.getSessionId(), json.length());
        } catch (IOException e) {
            log.error("持久化会话失败: {}", session.getSessionId(), e);
        }
    }

    @Override
    public Session getSession(AgentScope scope, String sessionId) {
        String key = cacheKey(scope != null ? scope.getTenantId() : null,
                scope != null ? scope.getUserId() : null, sessionId);
        Session cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        // 缓存未命中，尝试从文件加载
        Path file = sessionFile(scope, sessionId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Session session = JsonUtils.fromJson(json, Session.class);
            cache.put(key, session);
            return session;
        } catch (IOException e) {
            log.warn("从文件加载会话失败: {} -> {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<Session> listSessions(AgentScope scope) {
        String prefix = (scope != null ? scope.keyPrefix() : "default") + ":";
        List<Session> list = new ArrayList<>();
        cache.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                list.add(v);
            }
        });
        // 按更新时间倒序
        list.sort(Comparator.comparingLong(Session::getUpdateTime).reversed());
        return list;
    }

    @Override
    public void deleteSession(AgentScope scope, String sessionId) {
        cache.remove(cacheKey(scope != null ? scope.getTenantId() : null,
                scope != null ? scope.getUserId() : null, sessionId));
        Path file = sessionFile(scope, sessionId);
        try {
            Files.deleteIfExists(file);
            log.info("会话已删除: {}", sessionId);
        } catch (IOException e) {
            log.warn("删除会话文件失败: {}", sessionId, e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private Path sessionFile(AgentScope scope, String sessionId) {
        Path base = sessionsDir;
        String ns = scope != null ? scope.namespace() : null;
        if (ns != null) {
            base = base.resolve(ns);
        }
        return base.resolve(sessionId + ".json");
    }
}
