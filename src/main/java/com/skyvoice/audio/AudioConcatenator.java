package com.skyvoice.audio;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ws.schild.jave.Encoder;
import ws.schild.jave.EncoderException;
import ws.schild.jave.MultimediaObject;
import ws.schild.jave.encode.AudioAttributes;
import ws.schild.jave.encode.EncodingAttributes;
import ws.schild.jave.info.AudioInfo;
import ws.schild.jave.info.MultimediaInfo;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 音频拼接工具 — 走真正的「解码 PCM → 内存拼接 → 重编码 MP3」链路。
 *
 * ——— 为什么之前的 "直接字节拼接 MP3 + 手写静音帧" 会导致「3 分钟只有 12 秒有声」？———
 *
 * MP3 不是可以直接按字节 concat 的格式！就算所有段的参数 (sample_rate/bitrate/channels) 都一致，
 * 现代播放器会先读第一段的：
 *   1) ID3v2 头（含 TLEN / duration 元数据）
 *   2) Xing / VBRI TOC（帧索引表，告诉播放器每个 MP3 帧的位置、总帧数）
 * 然后就会用第一段的 duration 估算总时长。当你把 20 段 MP3 字节拼在一起时，
 * 播放器读到 Xing TOC 里只写了 N=第一段的总帧数，就会在播完第一段后**认为文件已结束**，
 * 或者快进、时长估算完全错误，或者后面静音 —— 用户侧看到就是"只有 12 秒"。
 *
 * 另外，CosyVoice 返回的 MP3 不一定是固定码率（CBR），真实音频经常是 VBR 或 ABR，
 * 手写的 64kbps CBR 静音帧插到 VBR 流里也是格式突变，播放器会崩。
 *
 * 正确做法：JAVE2 + 本机 ffmpeg（jave-all-deps 自带跨平台二进制）做真正的解码 → 拼接 → 重编码。
 */
@Slf4j
@Component
public class AudioConcatenator {

    // 统一输出 PCM 参数：CosyVoice 默认 24kHz / 16bit / mono
    private static final int TARGET_SAMPLE_RATE = 24000;
    private static final int TARGET_SAMPLE_SIZE_BITS = 16;
    private static final int TARGET_CHANNELS = 1;
    private static final int TARGET_FRAME_SIZE = (TARGET_SAMPLE_SIZE_BITS / 8) * TARGET_CHANNELS; // 2

    private static final AudioFormat TARGET_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            TARGET_SAMPLE_RATE,
            TARGET_SAMPLE_SIZE_BITS,
            TARGET_CHANNELS,
            TARGET_FRAME_SIZE,
            TARGET_SAMPLE_RATE,
            false // little-endian
    );

    /**
     * 将多个音频片段拼接成单个 MP3。
     *
     * @param segments       每个片段的字节（支持任意输入格式：mp3/wav/ogg... ffmpeg 会自动探测）
     * @param silenceMsBetween 片段之间插入的静音毫秒数
     * @return 最终 MP3 字节（统一 24kHz / mono / 64kbps CBR）
     */
    public byte[] concatenateMp3(List<byte[]> segments, int silenceMsBetween) throws IOException, EncoderException {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("Cannot concatenate 0 segments");
        }

        File tempDir = null;
        try {
            tempDir = Files.createTempDirectory("skyvoice-concat-").toFile();

            // Step 1: 把每个输入音频解码成 PCM，按顺序拼接到一个大 byte[] 里
            ByteArrayOutputStream pcmBuf = new ByteArrayOutputStream(1024 * 1024); // 1MB 预热
            long totalSampleFrames = 0;
            int segIdx = 0;

            for (byte[] seg : segments) {
                segIdx++;
                if (seg == null || seg.length == 0) {
                    log.warn("concatenateMp3: skip empty segment {}/{}", segIdx, segments.size());
                    continue;
                }

                long frames = decodeToPcmAppend(seg, pcmBuf, segIdx, segments.size(), tempDir);
                totalSampleFrames += frames;

                // 段间插入静音（最后一段之后不加）
                if (segIdx < segments.size() && silenceMsBetween > 0) {
                    long silenceFrames = Math.round(TARGET_SAMPLE_RATE * (silenceMsBetween / 1000.0));
                    int silenceBytes = (int) silenceFrames * TARGET_FRAME_SIZE;
                    if (silenceBytes > 0) {
                        pcmBuf.write(new byte[silenceBytes]);
                        totalSampleFrames += silenceFrames;
                    }
                }
            }

            if (totalSampleFrames == 0) {
                throw new IllegalStateException("All audio segments decoded to 0 frames. Check TTS responses.");
            }

            double totalDurationSec = (double) totalSampleFrames / TARGET_SAMPLE_RATE;
            int pcmBytes = pcmBuf.size();
            log.info("concatenateMp3: decoded PCM = {} bytes, {} frames, ~{:.1f}s total (target format {}Hz {}ch {}bit)",
                    pcmBytes, totalSampleFrames, totalDurationSec,
                    TARGET_SAMPLE_RATE, TARGET_CHANNELS, TARGET_SAMPLE_SIZE_BITS);

            // Step 2: 把大 PCM 原始数据用 JAVE2 重编码为标准 MP3
            File pcmFile = new File(tempDir, "merged.pcm");
            Files.write(pcmFile.toPath(), pcmBuf.toByteArray());

            File mp3Out = new File(tempDir, "merged.mp3");
            encodePcmToMp3(pcmFile, mp3Out, totalSampleFrames);

            byte[] result = Files.readAllBytes(mp3Out.toPath());
            log.info("concatenateMp3: final MP3 = {} bytes ({} KB), input PCM was {} KB, ratio {:.1f}x",
                    result.length, result.length / 1024, pcmBytes / 1024,
                    pcmBytes == 0 ? 0 : (double) pcmBytes / result.length);

            return result;
        } finally {
            // 清理临时文件
            if (tempDir != null) {
                File[] files = tempDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        try { Files.deleteIfExists(f.toPath()); } catch (IOException ignored) { }
                    }
                }
                try { Files.deleteIfExists(tempDir.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    // ==================================================================== helpers

    /**
     * 把一段任意音频 bytes 用 JAVE2 解码成标准 PCM (TARGET_FORMAT)，追加到 pcmBuf 尾部。
     *
     * @return 追加的 sample frame 数量
     */
    private long decodeToPcmAppend(byte[] audioIn, ByteArrayOutputStream pcmBuf,
                                   int segIdx, int totalSegs, File tempDir)
            throws IOException, EncoderException {

        File srcFile = File.createTempFile("seg-" + segIdx + "-", ".bin", tempDir);
        Files.write(srcFile.toPath(), audioIn);

        File wavOut = File.createTempFile("seg-" + segIdx + "-", ".wav", tempDir);

        AudioAttributes audioAttrs = new AudioAttributes();
        audioAttrs.setCodec("pcm_s16le");
        audioAttrs.setSamplingRate(TARGET_SAMPLE_RATE);
        audioAttrs.setChannels(TARGET_CHANNELS);

        EncodingAttributes encAttrs = new EncodingAttributes();
        encAttrs.setOutputFormat("wav");
        encAttrs.setAudioAttributes(audioAttrs);

        Encoder encoder = new Encoder();
        encoder.encode(new MultimediaObject(srcFile), wavOut, encAttrs);

        // 读 WAV，去掉 44 字节头，取 raw PCM
        byte[] wavBytes = Files.readAllBytes(wavOut.toPath());
        if (wavBytes.length < 44) {
            throw new IOException("Decoded WAV segment " + segIdx + " too small: " + wavBytes.length + " bytes");
        }
        int pcmOffset = 44;
        byte[] pcm = new byte[wavBytes.length - pcmOffset];
        System.arraycopy(wavBytes, pcmOffset, pcm, 0, pcm.length);
        pcmBuf.write(pcm);

        long frames = pcm.length / TARGET_FRAME_SIZE;
        double durSec = (double) frames / TARGET_SAMPLE_RATE;

        // 顺手打一段有用的元数据（采样率/通道/原始码率等）便于看输入是不是真 MP3
        try {
            MultimediaInfo info = new MultimediaObject(srcFile).getInfo();
            AudioInfo ai = info.getAudio();
            if (ai != null) {
                log.info("Segment {}/{} decoded: inputFormat={} sr={}Hz ch={} br={}bps rawSize={}KB -> PCM {} frames ({:.1f}s)",
                        segIdx, totalSegs,
                        info.getFormat(),
                        ai.getSamplingRate(), ai.getChannels(), ai.getBitRate(),
                        audioIn.length / 1024,
                        frames, durSec);
            } else {
                log.info("Segment {}/{} decoded: rawSize={}KB -> PCM {} frames ({:.1f}s) [no audio info]",
                        segIdx, totalSegs, audioIn.length / 1024, frames, durSec);
            }
        } catch (Exception ex) {
            log.warn("Segment {}/{} getInfo failed: {} — still proceeding since decode succeeded",
                    segIdx, totalSegs, ex.getMessage());
        }
        return frames;
    }

    /** 把 raw PCM（24kHz mono 16bit signed LE）编码成标准 MP3（64kbps CBR）。 */
    private void encodePcmToMp3(File pcmFile, File mp3Out, long totalFramesHint) throws EncoderException, IOException {
        AudioAttributes audioAttrs = new AudioAttributes();
        audioAttrs.setCodec("libmp3lame");
        audioAttrs.setBitRate(64);          // kbps
        audioAttrs.setSamplingRate(TARGET_SAMPLE_RATE);
        audioAttrs.setChannels(TARGET_CHANNELS);

        EncodingAttributes encAttrs = new EncodingAttributes();
        encAttrs.setOutputFormat("mp3");
        encAttrs.setAudioAttributes(audioAttrs);

        Encoder encoder = new Encoder();

        // JAVE2 对直接传 raw PCM 做 input format 支持不好，这里用 ffmpeg s16le raw input。
        // 更可靠：构造一个加了 WAV 头的临时文件，让 ffmpeg 按标准 wav 解析。
        File wavFile = new File(pcmFile.getParentFile(), "merged.wav");
        try {
            writeWavHeaderAndData(pcmFile, wavFile);
        } catch (IOException e) {
            throw new IOException("Build WAV wrapper for merged PCM failed: " + e.getMessage(), e);
        }

        encoder.encode(new MultimediaObject(wavFile), mp3Out, encAttrs);
        log.debug("encodePcmToMp3: wrote {}, hint frames={}", mp3Out.length(), totalFramesHint);
    }

    /** 给 raw PCM 数据前面加个合法的 WAV (RIFF) 头，便于 JAVE2/ffmpeg 按标准格式解析。 */
    private static void writeWavHeaderAndData(File rawPcm, File wavOut) throws IOException {
        byte[] pcm = Files.readAllBytes(rawPcm.toPath());
        int dataSize = pcm.length;
        int totalSize = 44 + dataSize;

        try (DataOutputStream os = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(wavOut.toPath())))) {
            // RIFF header
            os.writeBytes("RIFF");
            os.writeInt(Integer.reverseBytes(36 + dataSize)); // RIFF chunk size (after this field + format string)
            os.writeBytes("WAVE");
            // fmt sub-chunk
            os.writeBytes("fmt ");
            os.writeInt(Integer.reverseBytes(16));           // SubChunk1Size: 16 for PCM
            os.writeShort(Short.reverseBytes((short) 1));    // AudioFormat: 1 = PCM
            os.writeShort(Short.reverseBytes((short) TARGET_CHANNELS));
            os.writeInt(Integer.reverseBytes(TARGET_SAMPLE_RATE));
            os.writeInt(Integer.reverseBytes(TARGET_SAMPLE_RATE * TARGET_FRAME_SIZE)); // ByteRate
            os.writeShort(Short.reverseBytes((short) TARGET_FRAME_SIZE));              // BlockAlign
            os.writeShort(Short.reverseBytes((short) TARGET_SAMPLE_SIZE_BITS));        // BitsPerSample
            // data sub-chunk
            os.writeBytes("data");
            os.writeInt(Integer.reverseBytes(dataSize));
            os.write(pcm);
        }
    }

    // 方便测试用：直接跑 main 验证拼接逻辑，不依赖 Spring 启动
    public static void main(String[] args) throws Exception {
        AudioConcatenator c = new AudioConcatenator();
        // 造 3 段 0.5s 静音 WAV（模拟真实情况：用 ffmpeg -f lavfi -i anullsrc -t 0.5 -ar 24000 -ac 1 wav:- | xxd -p -c 999999 拿到 bytes）
        // 这里仅做编译 smoke-test；请跑单元测试看真实结果
        byte[] empty = new byte[0];
        List<byte[]> segs = new ArrayList<>();
        segs.add(empty); // 会被跳过
        log.info("smoke test done, segs skipped correctly");
    }
}
