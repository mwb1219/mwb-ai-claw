package com.mwb.ai.claw.infrastructure.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.mwb.ai.claw.domain.memory.LayeredMemoryConfig;
import com.mwb.ai.claw.domain.rag.RagConfig;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 默认配置属性（从 application.yml 读取，前缀 agent）
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    @PostConstruct
    public void printStartupConfig() {
        log.info("Agent 默认配置: id={}, name={}, model={}, baseUrl={}, apiKey={}, temperature={}, maxTokens={}, maxSteps={}, maxStepsExtension={}",
                agentId, name, model, baseUrl, mask(apiKey), temperature, maxTokens, maxSteps, maxStepsExtension);
        log.info("编排配置: 默认编排={}", orchestration);
        log.info("记忆/技能配置: 记忆目录={}, 技能开关={}, 技能目录={}", memoryDir, skillsEnabled, skillsDir);
        log.info("工具配置: tools={}", tools);
        log.info("专家 Agent 配置: 共 {} 个（{}）", agents.size(),
                agents.stream().map(AgentConfig::getAgentId).collect(Collectors.joining(", ")));
    }

    /** API Key 掩码：仅输出配置状态，不泄露实际值 */
    private static String mask(String apiKey) {
        return (apiKey == null || apiKey.trim().isEmpty()) ? "(未配置)" : "***";
    }

    /** Agent 标识 */
    private String agentId = "default";

    /** Agent 名称 */
    private String name = "mwb-ai-claw";

    /** 系统提示词 */
    private String systemPrompt = "你是一个乐于助人的 AI 助手。当需要时可以调用工具来完成任务。";

    /** 模型标识 */
    private String model = "gpt-4o";

    /** Provider 类型：openai / anthropic / gemini / ollama（默认 openai，未配置时完全向后兼容） */
    private String provider = "openai";

    /** OpenAI 兼容 API Base URL（空则由 Provider 推断默认，如 anthropic=https://api.anthropic.com/v1） */
    private String baseUrl = "https://api.openai.com/v1";

    /** API Key */
    private String apiKey = "";

    /** 采样温度 */
    private double temperature = 0.7;

    /** 单次最大 tokens */
    private int maxTokens = 2048;

    /** ReAct 最大推理步数 */
    private int maxSteps = 8;

    /** ReAct 步数扩展系数：初始预算用尽且工具链未完成时自动扩展，硬上限 = maxSteps × 系数（默认 2.0，>1 生效） */
    private double maxStepsExtension = 2.0;

    /** 默认编排 id（引用 orchestrations.json 中的 id；多 Agent 协作编排经 invoke_* 工具由主 Agent 自主发起），默认 routing */
    private String orchestration = "routing";

    /** 长期记忆目录（AGENT.md / MEMORY.md 存放位置，默认 ${user.dir}/.agent） */
    private String memoryDir = "";

    /** 技能总开关（默认 true；关闭后不加载技能、不注册 use_skill 工具） */
    private boolean skillsEnabled = true;

    /** 技能根目录（运行目录，默认 ${user.dir}/skills；classpath skills/ 为内置模板兜底） */
    private String skillsDir = "";

    /**
     * 可用工具名称列表；空 = 绑定全部已注册工具（内置 + 全局/MCP，缺省行为），
     * 非空 = 强制仅绑定声明的工具（不再自动附加全局工具）
     */
    private List<String> tools = new ArrayList<>();

    /** 专家 Agent 定义列表（多 Agent 路由） */
    private List<AgentConfig> agents = new ArrayList<>();

    /** 分层记忆配置（agent.memory.*） */
    private LayeredMemoryConfig memory = new LayeredMemoryConfig();

    /** 独立 RAG 配置（agent.rag.*） */
    private RagConfig rag = new RagConfig();

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

        /** 模型标识（可选，为空则继承默认） */
        private String model;

        /** Provider 类型（可选，为空则继承默认） */
        private String provider;

        /** API Base URL（可选，为空则继承默认） */
        private String baseUrl;

        /** API Key（可选，为空则继承默认） */
        private String apiKey;

        /** 采样温度（可选，为空则继承默认） */
        private Double temperature;

        /** 单次最大 tokens（可选，为空则继承默认） */
        private Integer maxTokens;
    }

    /**
     * 工具安全配置
     */
    private ToolSecurityConfig security = new ToolSecurityConfig();

    /**
     * 存储配置（agent.storage.*）
     */
    private StorageConfig storage = new StorageConfig();

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

        /** Shell 审批模式：auto（白名单内自动执行）| ask（命中审批规则时向用户确认，默认）| read-only（命中审批规则时拒绝执行） */
        private String shellApprovalMode = "ask";

        /** Shell 审批规则（子串匹配，不区分大小写）：ask 模式下命中即请求用户确认，read-only 模式下命中即拒绝 */
        private List<String> shellApprovalPatterns = new ArrayList<>(Arrays.asList(
                // Git 不可逆 / 变更操作
                "git push", "git reset", "git revert", "git clean", "git commit", "git rm", "git mv",
                // 文件系统变更
                "rm ", "mv ", "cp ", "chmod", "chown", "tee ", "dd ",
                // 进程管理
                "kill ",
                // 包管理（安装/卸载会改动系统或项目）
                "npm install", "npm uninstall", "npm remove",
                "pip install", "pip uninstall", "pip3 install",
                "brew install", "brew uninstall", "gem install",
                // 网络写操作
                "ssh ", "scp ", "curl -X", "curl --data", "curl --upload", "curl -F", "wget ",
                // 压缩 / 格式化 / 磁盘
                "tar ", "gzip", "gunzip", "zip ", "unzip", "mkfs", "fdisk", "> "
        ));

        /** 单个工具执行超时（秒） */
        private int toolTimeoutSeconds = 30;

        /** 工具输出最大长度（字符数），超出截断 */
        private int maxOutputLength = 10000;

        /** HTTP 请求允许的 host 模式（空列表表示全部允许） */
        private List<String> httpAllowedHosts = new ArrayList<>();

        /** 提示词注入防护（默认 true）：system prompt 追加内容边界约束段，防止外部内容诱导执行/套取指令 */
        private boolean promptInjectionGuard = true;
    }

    /**
     * 可观测性配置（agent.observability.*）
     */
    private ObservabilityConfig observability = new ObservabilityConfig();

    /**
     * LLM 韧性配置（agent.llm.*）
     */
    private LlmResilienceConfig llm = new LlmResilienceConfig();

    @Data
    public static class ObservabilityConfig {
        /** 每次运行用量 JSONL 记录开关（默认 true） */
        private boolean runUsageLog = true;

        /** 用量记录目录（默认 {memory-dir}/runs） */
        private String runUsageDir = "";

        /** 指标暴露方式：none | actuator | prometheus（提示性配置，实际暴露依赖 classpath 引入的依赖） */
        private String metricsExporter = "none";
    }

    @Data
    public static class LlmResilienceConfig {
        /** LLM HTTP 连接超时（毫秒） */
        private int connectTimeoutMs = 5000;

        /** LLM HTTP 读超时（毫秒，同步与流式共用） */
        private int readTimeoutMs = 120000;

        /** 重试配置（429 / 5xx / 网络错误） */
        private LlmRetryConfig retry = new LlmRetryConfig();

        /** 备用模型（留空关闭 fallback） */
        private String fallbackModel = "";

        /** 备用模型 Base URL（留空继承主模型） */
        private String fallbackBaseUrl = "";

        /** 备用模型 API Key（留空继承主模型） */
        private String fallbackApiKey = "";

        /** 单次运行累计 token 预算（prompt + completion，0 = 不限） */
        private long runBudgetTokens = 0;

        /** 单条消息最大 token 数（超出截断告警） */
        private int maxSingleMessageTokens = 12000;
    }

    @Data
    public static class LlmRetryConfig {
        /** 最大重试次数（0 = 关闭重试） */
        private int maxAttempts = 3;

        /** 初始退避（毫秒） */
        private long initialBackoffMs = 500;

        /** 最大退避（毫秒） */
        private long maxBackoffMs = 10000;
    }

    /**
     * 存储配置：后端类型选择
     */
    @Data
    public static class StorageConfig {
        /** 存储后端：file（本地文件，默认）| db（JDBC 持久化） */
        private String type = "file";
    }
}
