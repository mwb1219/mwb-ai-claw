package com.mwb.ai.claw.example.web.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mwb.ai.claw.example.web.model.User;
import com.mwb.ai.claw.infrastructure.config.AgentProperties;
import com.mwb.ai.claw.infrastructure.util.JsonUtils;

/**
 * 用户文件存储（storage 层 file 实现）：将用户保存为 {@code {memoryDir}/users.json}。
 * <p>
 * 内存缓存 + 写穿文件持久化，进程重启后自动加载。不承载业务规则，仅提供读写能力。
 */
public class FileUserStorage implements UserStorage {

    private static final Logger log = LoggerFactory.getLogger(FileUserStorage.class);

    private final Path storeFile;
    private final String tenantId;
    private final Object lock = new Object();
    private final List<User> users = new ArrayList<>();

    public FileUserStorage(AgentProperties properties, String tenantId) {
        this.tenantId = tenantId;
        String dir = properties.getMemoryDir();
        if (dir == null || dir.trim().isEmpty()) {
            dir = System.getProperty("user.dir") + "/.agent";
        }
        this.storeFile = Paths.get(dir).resolve("users.json");
    }

    @PostConstruct
    public void init() {
        synchronized (lock) {
            load();
            log.info("用户文件存储已初始化: 文件={}, 用户数={}", storeFile.toAbsolutePath(), users.size());
        }
    }

    private void load() {
        if (!Files.exists(storeFile)) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(storeFile), StandardCharsets.UTF_8);
            List<User> loaded = JsonUtils.fromJson(json, new TypeReference<List<User>>() {});
            users.clear();
            if (loaded != null) {
                users.addAll(loaded);
            }
        } catch (Exception e) {
            log.warn("加载用户文件失败: {} -> {}", storeFile.getFileName(), e.getMessage());
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storeFile.getParent());
            String json = JsonUtils.toJson(users);
            Files.write(storeFile, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("持久化用户文件失败: {}", storeFile.getFileName(), e);
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        synchronized (lock) {
            return users.stream()
                    .filter(u -> u.getUsername().equals(username))
                    .findFirst();
        }
    }

    @Override
    public User save(User user) {
        synchronized (lock) {
            for (int i = 0; i < users.size(); i++) {
                if (users.get(i).getUsername().equals(user.getUsername())) {
                    users.set(i, user);
                    persist();
                    return user;
                }
            }
            users.add(user);
            persist();
            return user;
        }
    }

    @Override
    public String[] resolveApiKey(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return null;
        }
        synchronized (lock) {
            for (User user : users) {
                if (apiKey.equals(user.getApiKey())) {
                    return new String[]{tenantId, user.getUsername()};
                }
            }
        }
        return null;
    }
}
