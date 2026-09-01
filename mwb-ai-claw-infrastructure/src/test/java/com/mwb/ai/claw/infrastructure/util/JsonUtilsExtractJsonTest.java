package com.mwb.ai.claw.infrastructure.util;

import com.mwb.ai.claw.domain.util.JsonUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * JsonUtils.extractJson 容错提取单测（D2）：markdown 围栏 / 前后缀文本 / 字符串转义。
 */
public class JsonUtilsExtractJsonTest {

    @Test
    public void testPlainObject() {
        assertEquals("{\"a\":1}", JsonUtils.extractJson("{\"a\":1}"));
    }

    @Test
    public void testPlainArray() {
        assertEquals("[1,2,3]", JsonUtils.extractJson("[1,2,3]"));
    }

    @Test
    public void testMarkdownFence() {
        String text = "结果如下：\n```json\n{\"name\":\"张三\",\"age\":30}\n```\n以上";
        assertEquals("{\"name\":\"张三\",\"age\":30}", JsonUtils.extractJson(text));
    }

    @Test
    public void testPrefixAndSuffix() {
        String text = "好的，这是你要的数据：{\"code\":0,\"msg\":\"成功\"}，请注意查收。";
        assertEquals("{\"code\":0,\"msg\":\"成功\"}", JsonUtils.extractJson(text));
    }

    @Test
    public void testEscapedBracesInString() {
        // 字符串内部的花括号不应参与配对
        assertEquals("{\"a\":\"{x}\",\"b\":1}", JsonUtils.extractJson("前缀 {\"a\":\"{x}\",\"b\":1} 后缀"));
    }

    @Test
    public void testEscapedQuoteInString() {
        assertEquals("{\"a\":\"he said \\\"hi\\\"\"}", JsonUtils.extractJson("{\"a\":\"he said \\\"hi\\\"\"}"));
    }

    @Test
    public void testNestedJson() {
        assertEquals("{\"a\":{\"b\":[1,{\"c\":2}]}}", JsonUtils.extractJson("数据: {\"a\":{\"b\":[1,{\"c\":2}]}} 完"));
    }

    @Test
    public void testNoJsonReturnsNull() {
        assertNull(JsonUtils.extractJson("没有任何 JSON 内容"));
        assertNull(JsonUtils.extractJson(null));
    }

    @Test
    public void testInvalidBalancedBlockReturnsNull() {
        // 括号平衡但内容非法
        assertNull(JsonUtils.extractJson("{\"a\":}"));
    }
}
