package com.mwb.ai.claw.domain.rag;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 文档解析后的标准结构。
 */
@Data
public class ParsedDocument {

    /** 解析出的章节列表，按标题层级组织。 */
    private List<Section> sections = new ArrayList<>();

    @Data
    public static class Section {
        /** 章节标题路径，如 "1.2 架构设计"。 */
        private String titlePath;
        /** 章节正文内容。 */
        private String content;

        public Section() {
        }

        public Section(String titlePath, String content) {
            this.titlePath = titlePath;
            this.content = content;
        }
    }
}
