package com.mwb.ai.claw.example.embed;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.util.StringUtils;

import com.mwb.ai.claw.domain.llm.LlmResponse;
import com.mwb.ai.claw.domain.llm.LlmStreamCallback;
import com.mwb.ai.claw.dto.ChatCmd;
import com.mwb.ai.claw.dto.SingleResponse;
import com.mwb.ai.claw.dto.data.ChatResponseDTO;
import com.mwb.ai.claw.infrastructure.util.ConfigFileLocator;
import com.mwb.ai.claw.runtime.ClawRuntime;

/**
 * 客户端嵌入式示例：演示 {@link ClawRuntime} 在无 Web 容器的 JVM 应用中直接调用 Agent 能力。
 * <p>
 * 配置加载顺序（与框架 {@link ConfigFileLocator} 一致）：
 * {@code .env}（运行目录 → 安装目录 ~/.mwb-ai-claw）→ 系统环境变量 → 内置默认值。
 * example-embed 为独立工程（不随 mwb-ai-claw 仓库 reactor 构建），复制 .env.example 为 .env
 * 并填入密钥后直接运行（pom.xml 已配置 exec 插件主类）：
 * <pre>{@code
 * cd example-embed
 * cp .env.example .env   # 填入 DEFAULT_API_KEY 等
 * mvn exec:java
 * }</pre>
 */
public class EmbedDemo {

    public static void main(String[] args) {
        // 1. 读取配置：.env（运行目录 / 安装目录）→ 系统环境变量 → 内置默认
        System.out.println("运行目录(user.dir): " + System.getProperty("user.dir")
                + "（.env 按此目录 → ~/.mwb-ai-claw 顺序加载）");
        Map<String, String> dotenv = loadDotenv();
        String apiKey = value(dotenv, "DEFAULT_API_KEY", "");
        String model = value(dotenv, "DEFAULT_MODEL", "deepseek-chat");
        String baseUrl = value(dotenv, "DEFAULT_BASE_URL", "https://api.deepseek.com/v1");
        System.out.println("已加载配置: model=" + model + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey.isEmpty() ? "(未配置)" : apiKey.substring(0, 6) + "***"));

        // 2. 构建运行时：配置 LLM（不启动任何 Web 容器 / 端口）；仅注入非空值，避免空串覆盖默认
        ClawRuntime.Builder builder = ClawRuntime.builder();
        if (StringUtils.hasText(apiKey)) {
            builder.apiKey(apiKey);
        }
        if (StringUtils.hasText(model)) {
            builder.model(model);
        }
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        try (ClawRuntime runtime = builder.build()) {

            // 3. 首次对话：自动创建会话
            ChatCmd first = new ChatCmd();
            first.setMessage("你好，请用一句话介绍你自己。");
            SingleResponse<ChatResponseDTO> resp = runtime.chat(first);
            if (resp.isSuccess()) {
                String sessionId = resp.getData().getSessionId();
                String reply = resp.getData().getReply();
                System.out.println("== 首次对话 ==");
                System.out.println("sessionId: " + sessionId);
                System.out.println("回复: " + reply);

                // 4. 追问：同一会话继续（上下文连续）
                SingleResponse<ChatResponseDTO> followUp = runtime.chat(sessionId, "你刚才说到了什么？请复述。");
                System.out.println("== 追问 ==");
                System.out.println("回复: " + (followUp.isSuccess() ? followUp.getData().getReply() : followUp.getErrMessage()));
            } else {
                System.out.println("对话失败: " + resp.getErrMessage());
            }

            // 5. 流式对话演示（增量 token 实时回调）
            demoChatStream(runtime);
        }
    }

    /** 流式对话演示：自动创建会话 + 复用会话追问，增量 token 经 LlmStreamCallback 实时输出 */
    private static void demoChatStream(ClawRuntime runtime) {
        System.out.println();
        System.out.println("== 流式对话（自动创建会话，增量输出）==");
        StringBuilder tokenBuffer = new StringBuilder();
        SingleResponse<ChatResponseDTO> streamResp = runtime.chatStream(
                "用一句话介绍你自己，并确认你支持流式输出。",
                null,
                new LlmStreamCallback() {
                    @Override
                    public void onToken(String token) {
                        System.out.print(token);
                        System.out.flush();
                        tokenBuffer.append(token);
                    }

                    @Override
                    public void onToolName(String toolName) {
                        System.out.println();
                        System.out.println("[工具调用] " + toolName);
                    }

                    @Override
                    public void onToolArguments(String argDelta) {
                        System.out.print(argDelta);
                        System.out.flush();
                    }

                    @Override
                    public void onComplete(LlmResponse response) {
                        System.out.println();
                        System.out.println("== 流式完成（聚合回复）==");
                        System.out.println(response.getContent());
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.out.println();
                        System.out.println("[流式错误] " + error.getMessage());
                    }
                });
        if (!streamResp.isSuccess()) {
            System.out.println("流式对话失败: " + streamResp.getErrMessage());
            return;
        }
        String streamSessionId = streamResp.getData().getSessionId();
        System.out.println("sessionId: " + streamSessionId);
        System.out.println("回调累计 token 文本: " + tokenBuffer);

        System.out.println();
        System.out.println("== 流式追问（复用同一会话，增量输出）==");
        SingleResponse<ChatResponseDTO> streamFollowUp = runtime.chatStream(
                streamSessionId, "请复述你上一条回复中提到的内容。",
                null,
                new LlmStreamCallback() {
                    @Override
                    public void onToken(String token) {
                        System.out.print(token);
                        System.out.flush();
                    }

                    @Override
                    public void onComplete(LlmResponse response) {
                        System.out.println();
                        System.out.println("== 流式追问完成 ==");
                        System.out.println(response.getContent());
                    }

                    @Override
                    public void onError(Throwable error) {
                        System.out.println();
                        System.out.println("[流式错误] " + error.getMessage());
                    }
                });
        if (!streamFollowUp.isSuccess()) {
            System.out.println("流式追问失败: " + streamFollowUp.getErrMessage());
        }
    }

    /** 读取 .env：运行目录 → 安装目录（ConfigFileLocator 统一加载） */
    private static Map<String, String> loadDotenv() {
        Map<String, String> map = new LinkedHashMap<>();
        String content = ConfigFileLocator.readConfigFile(".env");
        if (content == null || content.isEmpty()) {
            return map;
        }
        // 去除 UTF-8 BOM，避免首个 KEY 前混入 \uFEFF 导致解析失败
        if (content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        for (String line : content.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = trimmed.substring(0, idx).trim();
            String val = trimmed.substring(idx + 1).trim();
            // 去除首尾引号
            if (val.length() >= 2) {
                char first = val.charAt(0);
                char last = val.charAt(val.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    val = val.substring(1, val.length() - 1);
                }
            }
            map.put(key, val);
        }
        return map;
    }

    /** 取值优先级：.env → 系统环境变量 → 默认值 */
    private static String value(Map<String, String> dotenv, String key, String defaultValue) {
        String v = dotenv.get(key);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(key);
        }
        return (v == null || v.trim().isEmpty()) ? defaultValue : v;
    }
}
