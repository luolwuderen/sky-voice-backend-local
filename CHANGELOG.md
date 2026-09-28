## 增加支持「音色自选」功能
### 新增的三个能力 
#### 1️查询当前可用音色（GET /api/v1/tts/voices）

同时还有一个「TTS 配置速览」：
#### POST /generate 支持用户指定音色（voiceA / voiceB）
现在 GenerateRequest.java 新增了两个可选字段：
映射优先级在 QwenTTSProvider.getVoiceId() ：
1. 最高 ：用户本次请求传的 voiceA / voiceB （Level 1）
2. 次之 ： application.yml 里的 voice-a / voice-b （Level 2）
3. 兜底 ：模型+语言+speaker 默认查表（Level 3） 

## 官方音色库（VoiceCatalog）
   按模型系列和音色是否支持 SSML/Instruct/时间戳
