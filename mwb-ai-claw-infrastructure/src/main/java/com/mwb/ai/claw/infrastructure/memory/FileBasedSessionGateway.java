package com.mwb.ai.claw.infrastructure.memory;

import com.mwb.ai.claw.domain.core.Session;
import com.mwb.ai.claw.domain.memory.MemoryGateway;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
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

/**
 * 文件持久化版会话存储：每个会话保存为 .agent/sessions/<sessionId>.json。
 * <p>
 * 内存缓存 + 文件持久化，实现跨重启的会话隔离与持久化。
 * 替代原先纯内存的 {@link com.mwb.ai.claw.infrastructure.memory.MemoryGatewayImpl}。
 */
@Component("fileBasedSessionGateway")
public class FileBasedSessionGateway implements MemoryGateway {

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
            // 启动时加载所有已有会话到缓存
            java.util.stream.Stream<Path> files = Files.list(sessionsDir);
            try {
                files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try {
                        String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                        Session session = JsonUtils.fromJson(json, Session.class);
                        cache.put(session.getSessionId(), session);
                    } catch (Exception e) {
                        log.warn("加载会话文件失败: {} -> {}", p.getFileName(), e.getMessage());
                    }
                });
            } finally {
                files.close();
            }
            log.info("会话存储已初始化: 目录={}, 已加载 {} 个会话",
                    sessionsDir.toAbsolutePath(), cache.size());
        } catch (IOException e) {
            log.error("初始化会话存储失败", e);
        }
    }

    // ==================== MemoryGateway 实现 ====================

    @Override
    public void saveSession(Session session) {
        cache.put(session.getSessionId(), session);
        Path file = sessionFile(session.getSessionId());
        try {
            Files.createDirectories(sessionsDir);
            String json = JsonUtils.toJson(session);
            Files.write(file, json.getBytes(StandardCharsets.UTF_8));
            log.debug("会话已持久化: {} ({} bytes)", session.getSessionId(), json.length());
        } catch (IOException e) {
            log.error("持久化会话失败: {}", session.getSessionId(), e);
        }
    }

    @Override
    public Session getSession(String sessionId) {
        Session cached = cache.get(sessionId);
        if (cached != null) {
            return cached;
        }
        // 缓存未命中，尝试从文件加载
        Path file = sessionFile(sessionId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            Session session = JsonUtils.fromJson(json, Session.class);
            cache.put(sessionId, session);
            return session;
        } catch (IOException e) {
            log.warn("从文件加载会话失败: {} -> {}", sessionId, e.getMessage());
            return null;
        }
    }

    @Override
    public List<Session> listSessions() {
        List<Session> list = new ArrayList<>(cache.values());
        // 按更新时间倒序
        list.sort(Comparator.comparingLong(Session::getUpdateTime).reversed());
        return list;
    }

    @Override
    public void deleteSession(String sessionId) {
        cache.remove(sessionId);
        Path file = sessionFile(sessionId);
        try {
            Files.deleteIfExists(file);
            log.info("会话已删除: {}", sessionId);
        } catch (IOException e) {
            log.warn("删除会话文件失败: {}", sessionId, e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private Path sessionFile(String sessionId) {
        return sessionsDir.resolve(sessionId + ".json");
    }
}
