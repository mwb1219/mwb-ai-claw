package com.mwb.ai.claw.example.embed;

import java.util.LinkedHashMap;
import java.util.Map;

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
 * 复制根目录 .env.example 为 example-embed/.env 并填入密钥后直接运行：
 * <pre>{@code
 * cp example-embed/.env.example example-embed/.env   # 填入 DEFAULT_API_KEY 等
 * mvn -pl example-embed exec:java -Dexec.mainClass=com.mwb.ai.claw.example.embed.EmbedDemo
 * }</pre>
 */
public class EmbedDemo {

    public static void main(String[] args) {
        // 1. 读取配置：.env（运行目录 / 安装目录）→ 系统环境变量 → 内置默认
        Map<String, String> dotenv = loadDotenv();
        String apiKey = value(dotenv, "DEFAULT_API_KEY", "");
        String model = value(dotenv, "DEFAULT_MODEL", "deepseek-chat");
        String baseUrl = value(dotenv, "DEFAULT_BASE_URL", "https://api.deepseek.com/v1");

        // 2. 构建运行时：配置 LLM（不启动任何 Web 容器 / 端口）
        try (ClawRuntime runtime = ClawRuntime.builder()
                .apiKey(apiKey)
                .model(model)
                .baseUrl(baseUrl)
                .build()) {

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
