package com.mwb.ai.claw.domain.memory.layered.model;

/**
 * 记忆页：分层记忆的最小存储单元。
 * <p>
 * 类型：HOT（工作记忆原文）/ SUMMARY（历史块摘要）/ FACT（结构化事实，跨会话）/ RETRIEVED（检索召回页）/ ARCHIVE（会话原文归档，跨会话 RAG 数据源）。
 */
public class MemoryPage {

    public enum PageType {
        HOT, SUMMARY, FACT, RETRIEVED, ARCHIVE
    }

    private String pageId;

    private PageType type;

    /** 页内容（摘要文本 / 事实内容 / 检索片段） */
    private String content;

    /** 事实去重键（如 "用户偏好-语言"），仅 FACT 使用 */
    private String key;

    /** 重要度 0-1（仅 FACT 使用） */
    private double importance;

    /** 估算 token 数 */
    private int tokenCount;

    /** 所属会话 */
    private String sessionId;

    /** 摘要覆盖的消息区间 [blockStart, blockEnd)，仅 SUMMARY 使用 */
    private int blockStart;

    private int blockEnd;

    /** 创建时间（merge 时按此保留最新） */
    private long createTime = System.currentTimeMillis();

    /** 事实更新版本号（merge 去重时自增，仅 FACT 使用） */
    private int version = 1;

    public MemoryPage() {
    }

    public static MemoryPage summary(String pageId, String content, String sessionId,
                                     int blockStart, int blockEnd, int tokenCount) {
        MemoryPage page = new MemoryPage();
        page.pageId = pageId;
        page.type = PageType.SUMMARY;
        page.content = content;
        page.sessionId = sessionId;
        page.blockStart = blockStart;
        page.blockEnd = blockEnd;
        page.tokenCount = tokenCount;
        return page;
    }

    public static MemoryPage fact(String key, String content, double importance, String sessionId) {
        MemoryPage page = new MemoryPage();
        page.pageId = "fact-" + key;
        page.type = PageType.FACT;
        page.key = key;
        page.content = content;
        page.importance = importance;
        page.sessionId = sessionId;
        return page;
    }

    /** 会话原文归档页（跨会话档案 RAG 数据源） */
    public static MemoryPage archive(String pageId, String content, String sessionId,
                                     int blockStart, int blockEnd, int tokenCount) {
        MemoryPage page = new MemoryPage();
        page.pageId = pageId;
        page.type = PageType.ARCHIVE;
        page.content = content;
        page.sessionId = sessionId;
        page.blockStart = blockStart;
        page.blockEnd = blockEnd;
        page.tokenCount = tokenCount;
        return page;
    }

    public String getPageId() {
        return pageId;
    }

    public void setPageId(String pageId) {
        this.pageId = pageId;
    }

    public PageType getType() {
        return type;
    }

    public void setType(PageType type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public double getImportance() {
        return importance;
    }

    public void setImportance(double importance) {
        this.importance = importance;
    }

    public int getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(int tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getBlockStart() {
        return blockStart;
    }

    public void setBlockStart(int blockStart) {
        this.blockStart = blockStart;
    }

    public int getBlockEnd() {
        return blockEnd;
    }

    public void setBlockEnd(int blockEnd) {
        this.blockEnd = blockEnd;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
