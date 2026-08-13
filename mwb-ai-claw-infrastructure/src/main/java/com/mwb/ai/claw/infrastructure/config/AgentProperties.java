package com.mwb.ai.claw.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Agent 默认配置属性（从 application.yml 读取，前缀 agent）
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** Agent 标识 */
    private String agentId = "default";

    /** Agent 名称 */
    private String name = "mwb-ai-claw";

    /** 系统提示词 */
    private String systemPrompt = "你是一个乐于助人的 AI 助手。当需要时可以调用工具来完成任务。";

    /** 模型标识 */
    private String model = "gpt-4o";

    /** OpenAI 兼容 API Base URL */
    private String baseUrl = "https://api.openai.com/v1";

    /** API Key */
    private String apiKey = "";

    /** 采样温度 */
    private double temperature = 0.7;

    /** 单次最大 tokens */
    private int maxTokens = 2048;

    /** ReAct 最大推理步数 */
    private int maxSteps = 8;

    /** 长期记忆目录（AGENT.md / MEMORY.md 存放位置，默认 ${user.dir}/.agent） */
    private String memoryDir = "";

    /** 可用工具名称列表 */
    private List<String> tools = Arrays.asList("echo");

    /** 专家 Agent 定义列表（多 Agent 路由） */
    private List<AgentConfig> agents = new ArrayList<>();

    /**
     * 专家 Agent 配置（可覆盖默认 Agent 的部分字段）
     */
    @Data
    public static class AgentConfig {
        /** Agent 标识 */
        private String agentId;

        /** Agent 名称 */
        private String name;

        /** 能力描述（供 LLM 路由判断意图使用） */
        private String description;

        /** 规则路由关键词 */
        private List<String> keywords = new ArrayList<>();

        /** 系统提示词 */
        private String systemPrompt;

        /** 可用工具名称列表 */
        private List<String> tools = new ArrayList<>();

        /** ReAct 最大推理步数（可选，为空时继承默认值） */
        private Integer maxSteps;
    }

    /**
     * 工具安全配置
     */
    private ToolSecurityConfig security = new ToolSecurityConfig();

    @Data
    public static class ToolSecurityConfig {
        /** 是否启用安全沙箱 */
        private boolean enabled = true;

        /** 文件操作根目录（绝对路径或相对 user.home），FileTool 只能操作此目录下的文件 */
        private String workspaceDir = "";

        /** Shell 命令白名单（空列表表示全部禁止） */
        private List<String> shellWhitelist = new ArrayList<>(Arrays.asList(
                // 基础文件 & 目录
                "ls", "cat", "echo", "pwd", "mkdir", "touch", "rm", "cp", "mv",
                "cd", "chmod", "chown",
                // 文本处理
                "grep", "find", "wc", "head", "tail", "sort", "uniq", "cut",
                "tr", "tee", "diff", "sed", "awk", "xargs",
                // 压缩解压
                "tar", "gzip", "gunzip", "zip", "unzip",
                // 系统信息
                "date", "whoami", "env", "uname", "df", "du", "free", "ps",
                "lsof", "netstat", "ss", "top",
                // 网络调试
                "curl", "wget", "ping", "dig", "nslookup",
                // 版本管理
                "git", "nvm", "sdk", "pyenv",
                // 语言运行时
                "python3", "python", "node", "java", "javac", "go",
                // 包管理 & 构建
                "npm", "npx", "pip", "pip3", "mvn", "gradle", "make", "cargo",
                "brew", "gem",
                // 编辑器
                "nano", "vim", "code",
                // 进程管理
                "kill",
                // 数据库
                "sqlite3",
                // 远程操作（需黑名单严格限制）
                "ssh", "scp"
        ));

        /** Shell 命令黑名单（在白名单匹配后再检查，优先级更高） */
        private List<String> shellBlacklist = new ArrayList<>(Arrays.asList(
                // 危险删除
                "rm -rf /", "rm -rf ~", "rm -rf .",
                // 提权 & 关机
                "sudo", "su ", "shutdown", "reboot", "halt", "poweroff",
                // 磁盘操作
                "mkfs", "dd if=", "> /dev/sda", "mkfs.",
                // 权限 & 属主
                "chmod 777", "chown root", "chmod -R",
                // Fork bomb
                ":(){ :|:"
        ));

        /** 单个工具执行超时（秒） */
        private int toolTimeoutSeconds = 30;

        /** 工具输出最大长度（字符数），超出截断 */
        private int maxOutputLength = 10000;

        /** HTTP 请求允许的 host 模式（空列表表示全部允许） */
        private List<String> httpAllowedHosts = new ArrayList<>();
    }
}
