package com.mwb.ai.claw.infrastructure.tool.mcp;

import com.mwb.ai.claw.domain.tool.McpServerConfig;
import com.mwb.ai.claw.domain.tool.McpToolDef;
import com.mwb.ai.claw.domain.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 客户端管理器：负责 MCP Server 的启动、工具发现和注册。
 * <p>
 * 实现 {@link SmartInitializingSingleton}，在所有 Bean 初始化完成后启动 MCP Server 连接。
 * 发现的 MCP 工具会作为 {@link ToolExecutor} 注册到 Spring 容器（通过 {@link McpToolRegistrar}）。
 */
@Component
public class McpClientManager implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    /** Server 名 → 已注册的工具名列表（用于断开时注销） */
    private final Map<String, List<String>> serverTools = new ConcurrentHashMap<>();
    private final List<McpServerConfig> serverConfigs = new ArrayList<>();
    private final McpToolRegistrar toolRegistrar;

    public McpClientManager(McpServerConfigLoader configLoader, McpToolRegistrar toolRegistrar) {
        this.toolRegistrar = toolRegistrar;
        serverConfigs.addAll(configLoader.load());
    }

    @Override
    public void afterSingletonsInstantiated() {
        initializeAll();
    }

    /**
     * 初始化所有启用的 MCP Server
     */
    public void initializeAll() {
        for (McpServerConfig config : serverConfigs) {
            if (!config.isEnabled()) {
                log.info("MCP Server '{}' 已禁用，跳过", config.getName());
                continue;
            }
            try {
                initializeServer(config);
            } catch (Exception e) {
                log.error("MCP Server '{}' 初始化失败", config.getName(), e);
            }
        }
    }

    /**
     * 初始化单个 MCP Server 并注册其工具
     */
    public void initializeServer(McpServerConfig config) throws Exception {
        log.info("正在启动 MCP Server '{}' (transport: {})...", config.getName(), config.getTransport());

        McpClient client = new McpClient(config);
        client.initialize();
        clients.put(config.getName(), client);

        // 列出工具并注册（同时记录工具名，供断开时注销）
        List<McpToolDef> tools = client.listTools();
        List<String> toolNames = new ArrayList<>();
        for (McpToolDef toolDef : tools) {
            McpToolAdapter adapter = new McpToolAdapter(client, toolDef);
            toolRegistrar.register(adapter);
            toolNames.add(adapter.getName());
            log.info("  注册工具: {}", adapter.getName());
        }
        serverTools.put(config.getName(), toolNames);

        log.info("MCP Server '{}' 已就绪，共注册 {} 个工具", config.getName(), tools.size());
    }

    /**
     * 断开指定 MCP Server：关闭连接并注销其注册的工具。
     *
     * @return 是否存在并成功断开
     */
    public boolean disconnectServer(String serverName) {
        McpClient client = clients.remove(serverName);
        if (client == null) {
            return false;
        }
        try {
            client.close();
        } catch (Exception e) {
            log.warn("关闭 MCP 客户端异常: {}", serverName, e);
        }
        List<String> toolNames = serverTools.remove(serverName);
        if (toolNames != null) {
            for (String toolName : toolNames) {
                toolRegistrar.unregister(toolName);
            }
            log.info("已断开 MCP Server '{}'，注销 {} 个工具", serverName, toolNames.size());
        } else {
            log.info("已断开 MCP Server '{}'", serverName);
        }
        return true;
    }

    /**
     * 重连指定 MCP Server（先断开，再按配置重新初始化）
     */
    public boolean reconnectServer(String serverName) {
        McpServerConfig config = null;
        for (McpServerConfig c : serverConfigs) {
            if (c.getName().equals(serverName)) {
                config = c;
                break;
            }
        }
        if (config == null) {
            return false;
        }
        disconnectServer(serverName);
        try {
            initializeServer(config);
            return true;
        } catch (Exception e) {
            log.error("MCP Server '{}' 重连失败", serverName, e);
            return false;
        }
    }

    /**
     * 是否已连接指定 MCP Server
     */
    public boolean isConnected(String serverName) {
        return clients.containsKey(serverName);
    }

    /**
     * 指定 MCP Server 已注册的工具名列表（未连接返回空列表）
     */
    public List<String> getServerToolNames(String serverName) {
        List<String> names = serverTools.get(serverName);
        return names == null ? java.util.Collections.emptyList() : names;
    }

    /**
     * 全部已配置的 MCP Server 配置列表（含未连接的）
     */
    public List<McpServerConfig> getServerConfigs() {
        return serverConfigs;
    }

    /**
     * 获取所有已连接的 MCP 客户端
     */
    public Map<String, McpClient> getClients() {
        return clients;
    }

    /**
     * 获取指定 MCP Server 的客户端
     */
    public McpClient getClient(String serverName) {
        return clients.get(serverName);
    }

    /**
     * 销毁时关闭所有 MCP 连接
     */
    @PreDestroy
    public void destroy() {
        log.info("正在关闭所有 MCP 连接...");
        for (McpClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端异常: {}", client.getServerName(), e);
            }
        }
        clients.clear();
        log.info("所有 MCP 连接已关闭");
    }
}
