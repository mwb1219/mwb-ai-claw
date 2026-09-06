package com.mwb.ai.claw.eval.dataset;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Test;

import com.mwb.ai.claw.eval.model.EvalDataset;

public class DatasetLoaderTest {

    private final DatasetLoader loader = new DatasetLoader();
    private Path tmp;

    @Before
    public void setUp() throws Exception {
        tmp = Files.createTempDirectory("eval-dataset");
    }

    @Test
    public void load_json_parsesTaskAndCases() throws Exception {
        Path file = write("ds.json", "{"
                + "\"task\":{\"id\":\"qa-basic\",\"defaultJudge\":\"rule\"},"
                + "\"cases\":["
                + "{\"id\":\"c1\",\"prompt\":\"1+1=?\",\"expected\":\"2\",\"rule\":{\"type\":\"contains\",\"value\":\"2\"}},"
                + "{\"id\":\"c2\",\"prompt\":\"RAG 是?\",\"expected\":\"检索\"}"
                + "]}");

        EvalDataset ds = loader.load(file.toString());

        assertNotNull(ds);
        assertEquals("qa-basic", ds.getTask().getId());
        assertEquals(2, ds.getCases().size());
        assertEquals("c1", ds.getCases().get(0).getId());
        assertEquals(com.mwb.ai.claw.eval.model.RuleType.CONTAINS, ds.getCases().get(0).getRule().getType());
        assertEquals(com.mwb.ai.claw.eval.model.JudgeType.RULE, ds.getTask().getDefaultJudge());
    }

    @Test
    public void load_yaml_parses() throws Exception {
        Path file = write("ds.yaml", "task:\n"
                + "  id: qa-basic\n"
                + "  defaultJudge: llm\n"
                + "cases:\n"
                + "  - id: c1\n"
                + "    prompt: hello\n"
                + "    expected: world\n");

        EvalDataset ds = loader.load(file.toString());

        assertEquals("qa-basic", ds.getTask().getId());
        assertEquals(com.mwb.ai.claw.eval.model.JudgeType.LLM, ds.getTask().getDefaultJudge());
        assertEquals(1, ds.getCases().size());
    }

    @Test
    public void load_missingTaskId_throws() throws Exception {
        Path file = write("bad.json", "{\"task\":{\"name\":\"x\"},\"cases\":[]}");
        try {
            loader.load(file.toString());
            fail("应抛异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("task.id"));
        }
    }

    @Test
    public void load_duplicateCaseId_throws() throws Exception {
        Path file = write("dup.json", "{"
                + "\"task\":{\"id\":\"t\"},"
                + "\"cases\":["
                + "{\"id\":\"c1\",\"prompt\":\"a\",\"expected\":\"b\"},"
                + "{\"id\":\"c1\",\"prompt\":\"c\",\"expected\":\"d\"}]}");
        try {
            loader.load(file.toString());
            fail("应抛异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("重复"));
        }
    }

    @Test
    public void load_missingExpected_throws() throws Exception {
        Path file = write("noexp.json", "{\"task\":{\"id\":\"t\"},\"cases\":[{\"id\":\"c1\",\"prompt\":\"a\"}]}");
        try {
            loader.load(file.toString());
            fail("应抛异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("expected"));
        }
    }

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}