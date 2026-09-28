package com.skyvoice.tts;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 阿里云百炼 TTS 官方音色目录（模型和音色严格匹配，避免 418 voice/model mismatch）。
 *
 * 文档来源（更新时以官方为准）：
 *   - CosyVoice V3 / V2 音色列表: https://help.aliyun.com/zh/model-studio/cosyvoice-voice-list
 *   - 多模态交互套件音色列表 (CosyVoice V3 专属): https://help.aliyun.com/zh/model-studio/multimodal-timbre-list
 *   - Qwen-Audio-TTS 音色列表: https://help.aliyun.com/zh/model-studio/qwen-audio-tts-voice-list
 *
 * 使用方法：
 *   GET /api/v1/tts/voices?model=cosyvoice-v3-flash&lang=zh   -> 列出该模型该语言支持的音色
 *   然后把返回的 voice.id 填到 POST /api/v1/generate 的 voiceA / voiceB 字段。
 */
public final class VoiceCatalog {

    private VoiceCatalog() {}

    /** 单个音色的元信息。 */
    public record VoiceInfo(
            String id,
            String name,
            String gender,
            String age,
            String traits,
            List<String> languages,
            boolean ssml,
            boolean instruct,
            boolean timestamps
    ) {}

    // ==================== CosyVoice V3 (cosyvoice-v3-flash / cosyvoice-v3-plus) ====================
    // V3 Flash 和 V3 Plus 使用同一套音色库（官方文档列出的 v3 系列）。
    private static final List<VoiceInfo> COSY_V3 = List.of(
            // 标杆音色
            v("longanyang",   "龙安洋", "男", "20-30岁", "阳光大男孩；社交陪伴",  langs("zh", "en"), true, true, true),
            v("longanhuan",   "龙安欢", "女", "20-30岁", "欢脱元气女",               langs("zh", "en"), true, true, true),
            // 多模态套件专属音色（V3 后缀 _v3）
            v("longhuohuo_v3","龙火火", "男", "6-10岁",  "桀骜不驯男童；儿童陪伴",    langs("zh", "en"), false, false, false),
            v("longwangwang_v3","龙汪汪","男","6-15岁",  "台湾少年音；儿童陪伴",      langs("zh", "en"), false, false, false),
            v("longchuanshu_v3","龙川叔","男","35-40岁", "油腻搞笑叔；方言四川腔",    langs("zh", "en"), false, false, false),
            v("longanshuo_v3","龙安朔", "男", "20-25岁", "干净清爽男；陪伴闲聊",      langs("zh", "en"), false, false, false),
            v("longfeifei_v3","龙菲菲", "女", "20-25岁", "甜美娇气女",               langs("zh", "en"), false, false, false),
            // CosyVoice V3 多音色 (文档截取常用)
            v("longanhuan_v3","龙安欢(V3)","女","20-30岁","欢脱元气女，支持多地方言", langs("zh","en"), true, true, true),
            v("longxiaotong_v3","龙晓彤","女","30-35岁","知性御姐；新闻播报",        langs("zh","en"), true, true, true),
            v("longyueyue_v3","龙越越", "女", "20-25岁", "温柔知性女",               langs("zh","en"), true, true, true),
            v("longxiaobei_v3","龙小蓓","女","7-12岁",  "活泼女童；儿童内容",        langs("zh","en"), true, true, true),
            v("longbaobao_v3","龙宝宝", "男", "3-6岁",   "萌萌男幼童",                langs("zh","en"), true, true, true),
            v("longyingying_v3","龙莹莹","女","25-30岁","活力少女音",                langs("zh","en"), true, true, true),
            v("longyunyun_v3","龙云云", "女", "25-30岁", "温柔亲切女客服",            langs("zh","en"), true, true, true),
            v("longdada_v3", "龙大大", "男", "30-35岁",  "沉稳大叔；有声读物",        langs("zh","en"), true, true, true),
            v("longlili_v3", "龙丽丽", "女", "25-30岁",  "活力解说；广告促销",        langs("zh","en"), true, true, true),
            v("longqingtian_v3","龙晴天","女","18-22岁","青春活力校园音",             langs("zh","en"), true, true, true)
    );

    // ==================== CosyVoice V2 (cosyvoice-v2) —— 必须带 _v2 后缀 ====================
    private static final List<VoiceInfo> COSY_V2 = List.of(
            v("longxiaochun_v2","龙晓春","男","20-30岁","v2 标准男；中英",   langs("zh","en"), true, false, true),
            v("longwan_v2",     "龙婉",  "女","20-30岁","v2 标准女；中英",   langs("zh","en"), true, false, true),
            v("longbao_v2",     "龙宝",  "男","4-7岁",  "v2 男童",          langs("zh","en"), true, false, true),
            v("longxiaoxing_v2","龙小星","女","5-8岁",  "v2 女童；中日",     langs("zh","ja"), true, false, true),
            v("longbaozhu_v2",  "龙宝珠","男","18-25岁","v2 日语男；中日",   langs("zh","ja"), true, false, true),
            v("longshuang_v2",  "龙双",  "女","20-30岁","v2 双语女主",       langs("zh","en","ja"), true, false, true),
            v("longxiaoxiao_v2","龙潇潇","女","30-40岁","v2 知性御姐",       langs("zh","en"), true, false, true),
            v("longchenchen_v2","龙尘尘","男","25-30岁","v2 清朗男声",       langs("zh","en"), true, false, true),
            v("longshanshan_v2","龙姗姗","女","18-22岁","v2 甜美女声",       langs("zh","en"), true, false, true)
    );

    // ==================== Qwen-Audio-3.0-TTS (flash / plus) ====================
    private static final List<VoiceInfo> QWEN_AUDIO_3 = List.of(
            v("longanhuan_v3.6",  "龙安欢",      "女", "25岁",  "精品中文女",     langs("zh", "en"), true, true, true),
            v("longjielidou_v3.6","龙杰力豆",    "男", "5岁",   "天真男童；精品", langs("zh", "en"), true, true, true),
            v("loongeva_v3.6",    "LoongEva",    "女", "28岁",  "高智美音；精品英", langs("en"),      true, true, true),
            v("loongjohn",        "LoongJohn",   "男", "28岁",  "沉稳亲切美音；英", langs("en"),      true, true, true),
            // 基础音色命名规则前缀提示：完整列表请下载官方 Excel。这里给出常见语言的推荐基础音色占位
            v("qwen-audio-3.0-tts-flash-male-jp",   "日语推荐男基础音", "男", "20-30岁",
                    "基础音色，若 418 请下载官方 Excel 选具体音色", langs("ja"), false, false, false),
            v("qwen-audio-3.0-tts-flash-female-jp", "日语推荐女基础音", "女", "20-30岁",
                    "基础音色，若 418 请下载官方 Excel 选具体音色", langs("ja"), false, false, false)
    );

    // ==================== 查询 API ====================

    /** 按模型（如 cosyvoice-v3-flash / cosyvoice-v2 / qwen-audio-3.0-tts-flash）筛选音色。 */
    public static List<VoiceInfo> list(String model) {
        String m = model == null ? "" : model.toLowerCase();
        if (m.startsWith("qwen-audio-3.0-tts")) return QWEN_AUDIO_3;
        if (m.contains("cosyvoice-v2"))         return COSY_V2;
        // 默认：cosyvoice v3 (flash/plus)
        return COSY_V3;
    }

    /** 按模型 + 语言（zh / en / ja）筛选。lang 为 null/空 时不过滤语言。 */
    public static List<VoiceInfo> listByModelAndLang(String model, String lang) {
        Stream<VoiceInfo> s = list(model).stream();
        if (lang != null && !lang.isBlank()) {
            String l = lang.toLowerCase();
            s = s.filter(v -> v.languages().stream().anyMatch(x -> x.equalsIgnoreCase(l)));
        }
        return s.toList();
    }

    /** 模型系列在枚举中是否有专门记录（用于判断默认音色兜底时是哪组）。 */
    public static boolean isQwenAudio3(String model) {
        return model != null && model.toLowerCase().startsWith("qwen-audio-3.0-tts");
    }
    public static boolean isCosyV2(String model) {
        return model != null && model.toLowerCase().contains("cosyvoice-v2");
    }

    /** 当前可用的模型 key 列表（提示用）。 */
    public static List<String> supportedModelKeys() {
        return List.of(
                "cosyvoice-v3-flash", "cosyvoice-v3-plus",
                "cosyvoice-v2",
                "qwen-audio-3.0-tts-flash", "qwen-audio-3.0-tts-plus"
        );
    }

    // ==================== helpers ====================

    private static VoiceInfo v(String id, String name, String gender, String age, String traits,
                               List<String> langs, boolean ssml, boolean instruct, boolean timestamps) {
        return new VoiceInfo(id, name, gender, age, traits, langs, ssml, instruct, timestamps);
    }

    private static List<String> langs(String... ls) {
        return List.of(ls);
    }

    /** 给定模型+语言，取默认的 speaker 音色（和 QwenTTSProvider#getVoiceId 保持一致，仅用于前端展示默认值）。 */
    public static String defaultVoiceIdFor(String model, String lang, String speaker) {
        boolean isA = "A".equalsIgnoreCase(speaker);
        boolean isEn = lang != null && lang.toLowerCase().startsWith("en");
        boolean isJa = lang != null && lang.toLowerCase().startsWith("ja");
        if (isQwenAudio3(model)) {
            if (isEn) return isA ? "loongjohn" : "loongeva_v3.6";
            if (isJa) return isA ? "qwen-audio-3.0-tts-flash-male-jp" : "qwen-audio-3.0-tts-flash-female-jp";
            return isA ? "longanhuan_v3.6" : "longjielidou_v3.6";
        }
        if (isCosyV2(model)) {
            if (isJa) return isA ? "longbaozhu_v2" : "longxiaoxing_v2";
            if (isEn) return isA ? "longwan_v2" : "longxiaochun_v2";
            return isA ? "longxiaochun_v2" : "longwan_v2";
        }
        // CosyVoice V3 默认
        if (isJa) return isA ? "longyang_v3" : "longling_v3";
        return isA ? "longanyang" : "longanhuan";
    }

    public static Set<String> supportedLanguageCodes() {
        return Set.of("zh", "en", "ja");
    }
}
