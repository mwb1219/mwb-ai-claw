package com.mwb.ai.claw.eval.dataset;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.mwb.ai.claw.eval.model.EvalCase;
import com.mwb.ai.claw.eval.model.EvalDataset;

/**
 * 评测数据集加载器：从文件 / classpath 资源读取 JSON 或 YAML 数据集，映射为 {@link EvalDataset}。
 * <p>
 * 按扩展名选择解析器（.yaml/.yml → YAML，其余 → JSON）；枚举按名称大小写不敏感映射
 * （如 "rule" → {@code JudgeType.RULE}）。
 */
public class DatasetLoader {

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public DatasetLoader() {
        this.jsonMapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
        this.yamlMapper = new YAMLMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS);
    }

    /**
     * 加载数据集，并做基本校验（task.id 必填、case.id 唯一、prompt/expected 必填）。
     *
     * @param path 文件绝对/相对路径，或 classpath 资源路径（文件优先）
     * @return 解析并校验通过的数据集
     * @throws IllegalArgumentException 加载失败 / 格式非法 / 校验不通过
     */
    public EvalDataset load(String path) {
        try {
            byte[] bytes = readBytes(path);
            ObjectMapper mapper = isYaml(path) ? yamlMapper : jsonMapper;
            EvalDataset dataset = mapper.readValue(bytes, EvalDataset.class);
            validate(dataset);
            return dataset;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("数据集解析失败: " + path + " — " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new IllegalArgumentException("数据集读取失败: " + path + " — " + e.getMessage(), e);
        }
    }

    private byte[] readBytes(String path) throws IOException {
        File file = new File(path);
        if (file.exists()) {
            return Files.readAllBytes(file.toPath());
        }
        Resource resource = new ClassPathResource(path);
        try (java.io.InputStream in = resource.getInputStream()) {
            return toByteArray(in);
        }
    }

    private boolean isYaml(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".yaml") || lower.endsWith(".yml");
    }

    private byte[] toByteArray(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private void validate(EvalDataset dataset) {
        if (dataset == null || dataset.getTask() == null || isEmpty(dataset.getTask().getId())) {
            throw new IllegalArgumentException("数据集缺少 task.id");
        }
        Set<String> ids = new HashSet<>();
        for (EvalCase c : dataset.getCases()) {
            if (isEmpty(c.getId())) {
                throw new IllegalArgumentException("用例缺少 id");
            }
            if (!ids.add(c.getId())) {
                throw new IllegalArgumentException("用例 id 重复: " + c.getId());
            }
            if (isEmpty(c.getPrompt())) {
                throw new IllegalArgumentException("用例 [" + c.getId() + "] 缺少 prompt");
            }
            if (isEmpty(c.getExpected())) {
                throw new IllegalArgumentException("用例 [" + c.getId() + "] 缺少 expected");
            }
        }
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }
}