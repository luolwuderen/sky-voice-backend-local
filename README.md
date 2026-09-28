# Sky Voice Backend

文章 / 文档 → 多语言双主持人播客式音频生成器（后端）

```
输入文本 → LLM 生成对话脚本 → 逐段 TTS 合成 → 音频拼接 → 输出 MP3
```

---

## 快速开始

### 1. 环境要求

| 工具 | 最低版本 | 说明 |
|---|---|---|
| JDK | 21+ | 代码使用 Java 21 语法（switch expression 等） |
| Maven | 3.8+ | 构建与依赖管理 |
| 阿里云百炼 API Key | - | TTS 语音合成 |
| DeepSeek / OpenAI 兼容 API Key | - | LLM 对话脚本生成 |

> 获取密钥：
> - 百炼（TTS）：https://bailian.console.aliyun.com/ → API-KEY 管理
> - DeepSeek（LLM）：https://platform.deepseek.com → API Keys

### 2. 配置（关键！不配对会 400 / 401）

**推荐方式：环境变量**

```bash
# LLM（脚本生成）— 默认 DeepSeek
export LLM_API_KEY="sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
# 可选：自定义 endpoint / 模型（OpenAI 兼容的供应商也行）
# export LLM_API_URL="https://api.deepseek.com/v1/chat/completions"
# export LLM_MODEL="deepseek-chat"

# 阿里云百炼（TTS）
export QWEN_TTS_API_KEY="sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
# 强烈建议带 WorkspaceId 专用域名（否则可能 400 InvalidUrl）
# 北京：
export QWEN_TTS_API_URL="https://{你的WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
# 新加坡（出海）：
# export QWEN_TTS_API_URL="https://{你的WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
```

**WorkspaceId 获取位置**：百炼控制台 → 工作空间 → 空间设置 → 空间信息 → 空间 ID。

如果不想改环境变量，也可以直接编辑 [application.yml](file:///Users/libaocheng/Desktop/mine/sky-voice-backend/src/main/resources/application.yml)。

### 3. 启动

```bash
cd sky-voice-backend
mvn spring-boot:run
```

默认端口：**http://localhost:8080**

### 4. 调用 API 示例

#### 健康检查

```bash
curl http://localhost:8080/api/v1/health
```

#### 查看当前 TTS 配置（选音色前先跑它）

```bash
curl http://localhost:8080/api/v1/tts/info
```

返回：当前启用的 provider / model / apiUrl、5 种支持的模型 key、默认音色（按语言+speaker）。

#### 查询可选音色列表（POST generate 之前必查，避免 418 voice/model mismatch）

```bash
# 1) 用当前激活模型的默认语言不过滤（返回所有音色）
curl http://localhost:8080/api/v1/tts/voices

# 2) 指定模型 cosyvoice-v3-flash + 中文
curl "http://localhost:8080/api/v1/tts/voices?model=cosyvoice-v3-flash&lang=zh"

# 3) 指定模型 qwen-audio-3.0-tts-flash + 英文（精品音色 loongeva_v3.6 / loongjohn）
curl "http://localhost:8080/api/v1/tts/voices?model=qwen-audio-3.0-tts-flash&lang=en"

# 4) 指定模型 cosyvoice-v2 + 日文（v2 强制 *_v2 后缀音色）
curl "http://localhost:8080/api/v1/tts/voices?model=cosyvoice-v2&lang=ja"
```

返回结构：`{ count, defaultVoiceA, defaultVoiceB, voices: [{id,name,gender,age,traits,languages,ssml,instruct,timestamps}] }`。
选一个 `id`，填到下一个请求里的 `voiceA` / `voiceB`。

#### 提交生成任务（异步，立刻返回 taskId）— 支持自选音色

```bash
curl -X POST http://localhost:8080/api/v1/generate \
  -H "Content-Type: application/json" \
  -H "X-User-Id: test-user" \
  -d '{
    "inputType": "TEXT",
    "content": "近年来，人工智能在自然语言处理领域取得了突破性进展。大语言模型不仅能写作、翻译，还能进行复杂的推理。",
    "language": "zh",
    "targetMinutes": 3,
    "voiceA": "longanyang",
    "voiceB": "longanhuan_v3"
  }'
```

`voiceA` / `voiceB` 都为**可选**：
- 留空 → 走 application.yml 里配置的 `voice-a` / `voice-b`（如仍空 → 走模型+语言+speaker 的默认查表）。
- 如何选合法值：先跑 `GET /api/v1/tts/voices?model=xxx&lang=yyy`，返回的任一 `voices[].id`。

响应示例：

```json
{
  "taskId": "a1b2c3d4-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "status": "QUEUED",
  "message": "Task created, processing..."
}
```

#### 查询任务状态（**请加 `-i` 看状态码**，空 body 可能是 404 不是没响应）

```bash
curl -i http://localhost:8080/api/v1/tasks/b13fcef7-b321-4a70-9b3b-c0535b64cd7a
```

状态枚举：`QUEUED` → `GENERATING_SCRIPT` → `SYNTHESIZING_AUDIO` → `CONCATENATING` → `DONE` / `FAILED`

如返回 `HTTP/1.1 404` → 该 taskId 不在当前进程的内存里（通常是因为你用了旧进程的 taskId 去请求新进程，`taskStore` 是内存 HashMap，重启会清空；或者你请求的是另一个端口）。

#### 下载生成的 MP3

```bash
curl -O http://localhost:8080/api/v1/audio/{taskId}.mp3
```

如果想在本地文件系统直接找（macOS `java.io.tmpdir` 是带 hash 的随机路径）：

```bash
ls -la $(java -XshowSettings:properties -version 2>&1 \
         | grep java.io.tmpdir | awk "{print \$3}")/sky-voice-output/
```

---

## 常见问题：为什么 TTS 报错？（400 / 418 / 403 / 404）

这是踩坑最多的地方，下面按 HTTP 状态码列出已遇到的根因和解决方案：

### 418 Engine return error code

**含义**：`voice` 音色 ID 不正确，或者 `model`（模型）与 `voice`（音色）版本不匹配。

典型原因：
1. 把 CosyVoice V3 的音色（如 `longanyang`）用在 `cosyvoice-v2` 模型上 → v2 必须用 `_v2` 后缀。
2. 把 Qwen-Audio-3.0 的音色（如 `longanhuan_v3.6`）写进了 CosyVoice 模型。
3. 用已废弃的 v1 音色名（`longxiaochun` 无后缀、`Ethan`/`Chloe`/`lobo`/`Clover` 等只在老版文档出现）。

**解决方案**：
```bash
# 一步到位：查当前模型+语言的官方音色列表，然后从返回的 id 里挑
curl "http://localhost:8080/api/v1/tts/voices?model=cosyvoice-v3-flash&lang=zh"
```
代码层的映射在 [VoiceCatalog.java](file:///Users/libaocheng/Desktop/mine/sky-voice-backend/src/main/java/com/skyvoice/tts/VoiceCatalog.java) + [QwenTTSProvider.getVoiceId()](file:///Users/libaocheng/Desktop/mine/sky-voice-backend/src/main/java/com/skyvoice/tts/QwenTTSProvider.java#L151-L213)。

### 400 Bad Request

已经在代码里修复了 3 个常见根因（都在 [QwenTTSProvider.java](file:///Users/libaocheng/Desktop/mine/sky-voice-backend/src/main/java/com/skyvoice/tts/QwenTTSProvider.java)）：

**坑 1：`voice` 必须放在 `input` 对象里（不是 `parameters`）：**
```json
{ "input": { "text": "...", "voice": "longanyang" }, "parameters": { "format": "mp3" } }
```

**坑 2：Endpoint 建议带 WorkspaceId 专用域名：**
```
北京:  https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer
新加坡: https://{WorkspaceId}.ap-southeast-1.maas.aliyuncs.com/...
```
WorkspaceId 获取：百炼控制台 → 工作空间 → 空间设置 → 空间信息。

**坑 3：看日志定位**：仍 400 时 Spring Boot 控制台会打印：
```
Qwen TTS HTTP 400 BAD_REQUEST: request={...}, response={阿里云返回的完整错误 JSON}
```
把 `response=` 那段贴出去问，马上定位。

### 403 Forbidden — OSS SignatureDoesNotMatch（下载音频时）

典型错误：
```xml
<Code>SignatureDoesNotMatch</Code>
<StringToSign>GET\n\n\n1785671587\n/dashscope-result-bj/...</StringToSign>
```

**真正根因**：DashScope 返回的音频 URL 是**阿里云 OSS 预签名 URL**，`Signature` 是对「完整 URL 字符串 + HTTP Method + Expires 时间戳」算出来的。如果你做了下面任一件事，签名就会不匹配：

1. **把 `http://` 改成 `https://`（代码里以前做过这件事，已移除）**
2. 给请求再加 `Authorization: Bearer <DashScope Key>`（以前也做过，已移除）
3. URL 的 `Expires` 时间戳已经过期（重开任务拿新 URL 即可）
4. 复制粘贴修改了 URL 任意字符

**当前代码保证**：下载时使用 JDK 原生 `HttpURLConnection`，URL 一字不改、不添加自定义头、连接超时 60s。参见 [downloadBytesExact()](file:///Users/libaocheng/Desktop/mine/sky-voice-backend/src/main/java/com/skyvoice/tts/QwenTTSProvider.java#L224-L269)。

排查小技巧：如果还 403，复制报错里的 URL 在本机 `curl -v '<完整URL>'` 测一下，也 403 就说明问题在 OSS 侧过期或 bucket 策略，不是代码。

### 404 Not Found — 查询任务返回空（最常被当成「没报错」）

taskId 找不到的原因只有 2 个：
1. **进程重启了** → 任务存在内存 `ConcurrentHashMap` 里，重启清空（MVP 设计，后续换 PostgreSQL）。
2. **端口混乱** → macOS 上如果有多个 Java 进程同时跑，可能分别占 8080 和 8081。你 POST 的任务在进程 A（8081），却去进程 B（8080）查询，就永远 404。

**排查**：
```bash
# 看哪些端口被 Java 监听
lsof -iTCP:8080 -iTCP:8081 -sTCP:LISTEN -n -P

# 只留一个干净实例：全部杀掉重跑
kill $(lsof -tiTCP:8080 -sTCP:LISTEN) $(lsof -tiTCP:8081 -sTCP:LISTEN) 2>/dev/null
cd sky-voice-backend
mvn spring-boot:run
```

### 任务 DONE 了但我看不到文件？

音频保存在 `${java.io.tmpdir}/sky-voice-output/{taskId}.mp3`。macOS 的 `java.io.tmpdir` 不是 `/tmp`，用这条命令列真实文件：
```bash
find $(java -XshowSettings:properties -version 2>&1 | grep java.io.tmpdir | awk "{print \$3}") \
     -name "*.mp3" -mtime -1
```
或者直接用 `curl -O http://localhost:8080/api/v1/audio/{taskId}.mp3` 下载。

---

## 预置音色速查表

**优先用 API 查（永远最新）**：`GET /api/v1/tts/voices?model=...&lang=...`。下面是代码 [VoiceCatalog.java](file:///Users/libaocheng/Desktop/mine/sky-voice-backend/src/main/java/com/skyvoice/tts/VoiceCatalog.java) 里收录的常用默认值。

### CosyVoice V3（cosyvoice-v3-flash / cosyvoice-v3-plus）— 默认

| 语言 | Speaker A 默认（语义：主叙事人） | Speaker B 默认（语义：分析者） | 其它常用（从 API 查完整） |
|---|---|---|---|
| 中文 | `longanyang` 男，20-30 阳光大男孩 | `longanhuan` 女，20-30 欢脱元气女 | longfeifei_v3 / longanshuo_v3 / longdada_v3 / longxiaotong_v3 / longhuohuo_v3 / longwangwang_v3 / longchuanshu_v3 等 |
| 英文 | `longanyang`（双语） | `longanhuan`（双语） | 所有支持 `languages` 含 `en` 的 V3 音色 |
| 日文 | `longyang_v3`（基础音色占位，若 418 请查 API 具体选） | `longling_v3` | 提交任务前显式配置 voice-a/voice-b |

### Qwen-Audio-3.0-TTS（flash / plus）

| 语言 | Speaker A 默认 | Speaker B 默认 | 备注 |
|---|---|---|---|
| 中文 | `longanhuan_v3.6` 女精品 | `longjielidou_v3.6` 男童精品 | 官方系统音色 |
| 英文 | `loongjohn` 男，沉稳亲切美音 | `loongeva_v3.6` 女，高智美音 | 官方系统音色 |
| 日文 | 使用「基础音色」（完整列表请下载官方 Excel） | — | 推荐用 voiceA/voiceB 显式传 |

### CosyVoice V2（*强制 _v2 后缀*，否则 418）

| 语言 | Speaker A | Speaker B |
|---|---|---|
| 中文 | `longxiaochun_v2` 男 | `longwan_v2` 女 |
| 英文 | `longwan_v2` | `longxiaochun_v2` |
| 日文 | `longbaozhu_v2` 男 | `longxiaoxing_v2` 女 |

如果你有长期固定的音色偏好，推荐写在 `application.yml`：

```yaml
sky-voice:
  tts:
    qwen:
      model: cosyvoice-v3-flash
      voice-a: "longanyang"
      voice-b: "longfeifei_v3"
```

`application.yml` 的优先级 < **请求级 voiceA/voiceB 覆盖**（单次任务可选不同音色）。

---

## 模型选择速查

| 模型 | 场景 | 配套音色前缀 | 计费档位 |
|---|---|---|---|
| `cosyvoice-v3-flash` | 默认推荐，速度快 | CosyVoice V3（无后缀或 *_v3） | 低 |
| `cosyvoice-v3-plus` | 更高音质、情感表现 | CosyVoice V3（无后缀或 *_v3） | 中 |
| `qwen-audio-3.0-tts-flash` | 实时/短时、多语种多音色、细粒度标签控制 | longanhuan_v3.6 / loongeva_v3.6 / loongjohn 等 V3.6 系列 | 低 |
| `qwen-audio-3.0-tts-plus` | 高质量长音频、旗舰音色 | 和 flash 音色库共享，plus 专属旗舰音色另见 API | 中 |
| `cosyvoice-v2` | 兼容老项目或必须用 *_v2 音色 | 强制 `_v2` 后缀 | 低/中 |

在 `application.yml` 修改：

```yaml
sky-voice:
  tts:
    qwen:
      model: cosyvoice-v3-flash
```

---

## 项目结构

```
sky-voice-backend/
├── pom.xml                                      # Maven 配置（含 Lombok 注解处理器）
├── src/main/java/com/skyvoice/
│   ├── SkyVoiceApplication.java                 # 启动入口，@EnableAsync
│   ├── config/
│   │   └── AppConfig.java                       # sky-voice.* 配置属性绑定
│   ├── controller/
│   │   ├── GenerateController.java              # /api/v1/generate、/tasks、/audio
│   │   ├── TTSVoiceController.java              # /api/v1/tts/voices、/tts/info（查音色目录）
│   │   └── HealthController.java                # /api/v1/health
│   ├── service/
│   │   ├── LLMService.java                      # LLM 生成双主持人对话脚本
│   │   └── PipelineService.java                 # 核心管线（异步 @Async 编排，支持 voiceA/voiceB 覆盖）
│   ├── tts/
│   │   ├── TTSProvider.java                     # TTS 适配器接口 + TTSException
│   │   ├── QwenTTSProvider.java                 # 阿里云百炼 HTTP 实现（当前默认）
│   │   ├── VoiceCatalog.java                    # 官方音色目录常量 + 按模型/语言过滤
│   │   └── FishAudioProvider.java               # Fish Audio（已注释，备用扩展）
│   ├── audio/
│   │   └── AudioConcatenator.java               # MP3 字节流拼接 + 段间静音
│   ├── model/
│   │   ├── DialogueScript.java                  # { turns: [{speaker, text}] }
│   │   └── GenerationTask.java                  # 任务状态实体（内存存储）
│   └── dto/
│       ├── GenerateRequest.java                 # 提交任务请求 DTO（新增 voiceA/voiceB 可选）
│       ├── GenerateResponse.java                # 提交任务响应 DTO
│       └── TaskStatusResponse.java              # 查询状态响应 DTO
└── src/main/resources/
    ├── application.yml                          # 所有可配置项
    └── prompts/
        └── dialogue_system.txt                  # LLM System Prompt（决定对话风格）
```

---

## 核心管线（Pipeline）

```
用户 POST /api/v1/generate
  body 可选: { voiceA, voiceB } ← 本次请求指定的 Speaker A/B 音色 id
       ↓
GenerateController
  → 获取 inputText、targetMinutes、voiceA, voiceB
       ↓
PipelineService.createTask()
  → 生成 taskId，状态 QUEUED，立刻返回
       ↓ （@Async 新线程执行）
PipelineService.executePipeline(taskId, inputText, language, targetMinutes, voiceAOverride, voiceBOverride)
  │
  ├─ Step 1: GENERATING_SCRIPT
  │    LLMService.generateDialogueScript()
  │      → POST 到 DeepSeek，system prompt 引导生成 JSON 对话数组
  │      → 解析为 DialogueScript{ turns: [{speaker:A|B, text}] }
  │
  ├─ Step 2: SYNTHESIZING_AUDIO
  │    for each turn in turns:
  │       TTSProvider.getVoiceId(language, speaker, voiceAOverride, voiceBOverride)
  │         优先级: 本次请求 voiceA/voiceB > application.yml voice-a/voice-b > 模型+语言默认查表
  │         映射表见 VoiceCatalog.java
  │       TTSProvider.synthesize(text, voiceId, lang)
  │         → QwenTTSProvider: DashScope HTTP (voice 在 input 对象里，URL 原样下载 OSS 预签名)
  │         → 返回 byte[] MP3
  │
  ├─ Step 3: CONCATENATING
  │    AudioConcatenator.concatenateMp3(segments, silenceMs)
  │      → 段间插静音，输出合并后 byte[]
  │
  ├─ Step 4: 保存到 ${java.io.tmpdir}/sky-voice-output/{taskId}.mp3
  │
  └─ Step 5: 状态置 DONE，填充 audioUrl / durationSeconds / completedAt
              或失败置 FAILED + errorMessage
```

---

## 后续 TODO（MVP 外）

- [ ] 实现 URL 内容抓取（建议 Jsoup）
- [ ] 实现 PDF 文本提取（建议 PDFBox）
- [ ] 接入鉴权（Firebase Auth / JWT）
- [ ] 用量控制：Free 3 次 / 月，Pro 50 次 / 月
- [ ] 任务存储从 `ConcurrentHashMap` 迁移到 PostgreSQL
- [ ] 音频拼接升级：WAV 混合 + 首尾淡入淡出
- [ ] IAP（应用内购买）Receipt 校验
