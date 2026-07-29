package com.example.aiagent.service;

import com.example.aiagent.config.LlmProperties;
import com.example.aiagent.model.ModelOption;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ChatModelCatalog {
    public static final String FLASH = "deepseek-v4-flash";
    public static final String PRO = "deepseek-v4-pro";
    private static final Set<String> IDS = Set.of(FLASH, PRO);
    private static final List<ModelOption> OPTIONS = List.of(
        new ModelOption(
            FLASH,
            "DeepSeek V4 Flash",
            "响应更快，适合日常问答、检索总结与高频业务咨询。",
            "快速",
            true
        ),
        new ModelOption(
            PRO,
            "DeepSeek V4 Pro",
            "推理预算更高，适合复杂分析、跨文档归纳与决策建议。",
            "深度推理",
            false
        )
    );

    private final LlmProperties properties;

    public ChatModelCatalog(LlmProperties properties) {
        this.properties = properties;
    }

    public List<ModelOption> options() {
        return OPTIONS;
    }

    public String defaultModel() {
        return resolve(properties.model());
    }

    public String resolve(String requested) {
        String candidate = StringUtils.hasText(requested)
            ? requested.trim()
            : (StringUtils.hasText(properties.model()) ? properties.model().trim() : FLASH);
        if (!IDS.contains(candidate)) {
            throw new IllegalArgumentException("不支持的模型：" + candidate);
        }
        return candidate;
    }
}
