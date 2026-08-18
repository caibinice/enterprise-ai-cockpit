package com.example.aiagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredChunkerTest {
    @Test
    void carriesHeadingsIntoChunksAndRemovesExactDuplicates() {
        String repeated = "退款申请需要订单号、付款凭证和签收日期。"
            + "审核人员应核对客户身份、订单状态与退款原因。"
            + "资料完整后进入审批，资料缺失时一次性告知补充项。";
        StructuredChunker chunker = new StructuredChunker(240, 0);

        var chunks = chunker.split("# 退款审批规则\n\n" + repeated + "\n\n" + repeated);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0))
            .startsWith("章节：退款审批规则")
            .contains(repeated);
    }

    @Test
    void splitsOversizedSectionsWithoutLosingTheirContext() {
        StructuredChunker chunker = new StructuredChunker(240, 40);
        String content = "## 合同风险控制\n\n" + "每份合同都必须记录责任人和复核结论。".repeat(30);

        var chunks = chunker.split(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> chunk.contains("章节：合同风险控制"));
        assertThat(String.join("", chunks)).contains("责任人和复核结论");
    }
}
