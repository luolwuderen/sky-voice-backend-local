package com.skyvoice.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM生成的对话脚本数据结构
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DialogueScript {

    private List<DialogueTurn> turns;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DialogueTurn {
        private String speaker;  // "A" or "B"
        private String text;
    }

    /**
     * 估算音频时长（秒）
     * 注意：必须和 LLMService.estimateTargetChars 的语速基准一致，否则 durationSeconds 会严重偏长/偏短。
     * 中文 ~250字/分钟 → 4.17字/秒；日文 ~220字/分钟 → 3.67字/秒；英文 ~900字符/分钟 → 15字符/秒
     */
    public int estimateDurationSeconds(String language) {
        if (turns == null || turns.isEmpty()) return 0;
        int totalChars = turns.stream()
                .mapToInt(t -> t.getText() == null ? 0 : t.getText().length())
                .sum();

        double charsPerSecond = switch (language.toLowerCase()) {
            case "zh", "chinese", "中文" -> 250.0 / 60.0;
            case "ja", "japanese", "日本語" -> 220.0 / 60.0;
            default -> 900.0 / 60.0;
        };

        return (int) Math.ceil(totalChars / charsPerSecond);
    }
}
