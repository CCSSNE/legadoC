// @name Xiaomi MiMo V2.5 TTS
// @schema 1
// @version 1.0.1
// @uuid mimo_v25_tts
// @author Legado
// @url https://api.xiaomimimo.com/v1
// @enabled false
// @cookieJar false
// @audioType audio/x-wav
// @defaultSpeed 50
// @defaultVolume 50
// @defaultPitch 50
// @sampleText 前不见古人，后不见来者。念天地之悠悠，独怆然而涕下。
// @capabilities style_tags,emotion,emotion_intensity
// @description 小米 MiMo V2.5 预置音色 TTS 模板。当前按非流式 wav/base64 接入，不处理 stream=true 的 PCM 增量音频。

var MIMO_STYLE_OPTIONS = [
    { id: "neutral", name: "自然", value: "自然清晰地朗读，语气贴合文本。", tag: "" },
    { id: "calm", name: "平静", value: "用平静、克制、稳定的语气朗读。", tag: "(平静)" },
    { id: "gentle", name: "温柔", value: "用温柔、柔和、亲近的语气朗读。", tag: "(温柔)" },
    { id: "cheerful", name: "欢快", value: "用轻快上扬、明亮有活力的语气朗读。", tag: "(欢快)" },
    { id: "serious", name: "严肃", value: "用严肃、沉稳、有分量的语气朗读。", tag: "(严肃)" },
    { id: "cold", name: "冷漠", value: "用冷淡、疏离、情绪收敛的语气朗读。", tag: "(冷漠)" },
    { id: "sad", name: "悲伤", value: "用低落、压抑、带一点哽咽感的语气朗读。", tag: "(悲伤)" },
    { id: "angry", name: "愤怒", value: "用压抑但明显愤怒的语气朗读，重音更强。", tag: "(愤怒)" },
    { id: "excited", name: "兴奋", value: "用兴奋、节奏稍快、语调上扬的语气朗读。", tag: "(兴奋)" },
    { id: "whisper", name: "耳语", value: "用低声、贴近耳边的耳语感朗读。", tag: "(耳语)" },
    { id: "young", name: "少年感", value: "用更年轻、明亮、轻快的少年感朗读。", tag: "(稚嫩 清亮)" },
    { id: "mature", name: "成熟", value: "用成熟、稳重、气息更沉的语气朗读。", tag: "(成熟 沉稳)" },
    { id: "narration", name: "旁白", value: "用适合小说旁白的稳定叙事语气朗读，少夸张表演。", tag: "(深沉 平静)" }
];

var MIMO_VOICES = [
    {
        id: "冰糖",
        name: "冰糖",
        language: "zh-CN",
        gender: "female",
        profile: "活泼少女",
        roleHints: ["少女", "年轻女主", "活泼配角", "对白女"],
        sampleText: "嘿嘿，怎么样，我就说我能赢吧，这下你可输定啦！"
    },
    {
        id: "茉莉",
        name: "茉莉",
        language: "zh-CN",
        gender: "female",
        profile: "知性女声",
        roleHints: ["旁白", "成熟女性", "老师", "冷静女主"],
        sampleText: "夜色渐深，她合上书页，终于意识到这场风波才刚刚开始。"
    },
    {
        id: "苏打",
        name: "苏打",
        language: "zh-CN",
        gender: "male",
        profile: "阳光少年",
        roleHints: ["少年", "年轻男主", "同学", "对白男"],
        sampleText: "别担心，这件事交给我。天亮之前，我们一定能赶到。"
    },
    {
        id: "白桦",
        name: "白桦",
        language: "zh-CN",
        gender: "male",
        profile: "成熟男声",
        roleHints: ["旁白", "成熟男性", "长辈", "反派", "稳重男主"],
        sampleText: "他没有立刻回答，只是望着远处的灯火，缓缓叹了一口气。"
    }
];

function options() {
    return [
        { key: "apiKey", label: "API Key", type: "password", defaultValue: "" },
        { key: "baseUrl", label: "服务地址", type: "text", defaultValue: "https://api.xiaomimimo.com/v1" },
        {
            key: "model",
            label: "模型",
            type: "select",
            defaultValue: "mimo-v2.5-tts",
            values: [
                { label: "mimo-v2.5-tts", value: "mimo-v2.5-tts" }
            ]
        },
        { key: "timeout", label: "超时秒数", type: "number", defaultValue: "60" }
    ];
}

function voices(options, ctx) {
    var result = [];
    for (var i = 0; i < MIMO_VOICES.length; i++) {
        var item = MIMO_VOICES[i];
        result.push({
            id: item.id,
            name: item.name,
            language: item.language,
            gender: item.gender,
            style: item.profile,
            tags: ["mimo", "v2.5", item.profile].concat(item.roleHints),
            sample_text: item.sampleText,
            extra: {
                provider: "xiaomi_mimo",
                model: "mimo-v2.5-tts",
                profile: item.profile,
                roleHints: item.roleHints,
                styles: MIMO_STYLE_OPTIONS
            }
        });
    }
    return result;
}

function baseUrl(options) {
    return String(options.baseUrl || "https://api.xiaomimimo.com/v1").replace(/\/+$/, "");
}

function styleTag(voice) {
    var tag = voice && voice.style_tag ? String(voice.style_tag) : "";
    return tag.replace(/^\s+|\s+$/g, "");
}

function selectedStyleInstruction(voice) {
    var value = voice && voice.style_value ? String(voice.style_value) : "";
    if (value && value !== "neutral") {
        return value;
    }
    return "";
}

function speedInstruction(params) {
    var speed = Number(params && params.speed != null ? params.speed : 50);
    if (speed >= 80) {
        return "语速明显加快，但保持咬字清楚。";
    }
    if (speed >= 65) {
        return "语速稍快，节奏更紧凑。";
    }
    if (speed <= 20) {
        return "语速明显放慢，停顿更充分。";
    }
    if (speed <= 35) {
        return "语速偏慢，表达更从容。";
    }
    return "";
}

function expressiveInstruction(ctx) {
    var expressive = ctx && ctx.synthesis && ctx.synthesis.expressive
        ? ctx.synthesis.expressive
        : null;
    if (!expressive) {
        return "";
    }
    var parts = [];
    var concepts = expressive.style_concepts || [];
    if (concepts.length) {
        parts.push("本句表达风格：" + concepts.join("、") + "。");
    }
    if (expressive.emotion) {
        var emotionText = "本句情绪：" + String(expressive.emotion);
        var intensity = Number(expressive.intensity);
        if (expressive.intensity != null && !isNaN(intensity)) {
            emotionText += intensity >= 0.67 ? "，强烈" : (intensity >= 0.34 ? "，中等" : "，轻微");
        }
        parts.push(emotionText + "。");
    }
    return parts.join("\n");
}

function buildUserInstruction(voice, params, options, ctx) {
    var parts = [];
    var styleInstruction = selectedStyleInstruction(voice);
    var rateInstruction = speedInstruction(params);
    var dynamicInstruction = expressiveInstruction(ctx);
    var profile = voice && voice.extra && voice.extra.profile ? String(voice.extra.profile) : "";
    if (profile) {
        parts.push("当前基础音色画像：" + profile + "。");
    }
    if (styleInstruction) {
        parts.push(styleInstruction);
    }
    if (rateInstruction) {
        parts.push(rateInstruction);
    }
    if (dynamicInstruction) {
        parts.push(dynamicInstruction);
    }
    return parts.join("\n");
}

function withStyleTag(text, voice) {
    var tag = styleTag(voice);
    if (!tag) {
        return text;
    }
    return tag + text;
}

function synthesize(text, voice, params, options, ctx) {
    var apiKey = String(options.apiKey || "").replace(/^\s+|\s+$/g, "");
    if (!apiKey) {
        throw "请先在 Xiaomi MiMo V2.5 TTS 引擎选项中填写 API Key";
    }
    var voiceId = voice && voice.id ? String(voice.id) : "白桦";
    var messages = [];
    var userInstruction = buildUserInstruction(voice, params, options, ctx);
    if (userInstruction) {
        messages.push({
            role: "user",
            content: userInstruction
        });
    }
    messages.push({
        role: "assistant",
        content: withStyleTag(String(text || ""), voice)
    });
    return {
        url: baseUrl(options) + "/chat/completions",
        method: "POST",
        headers: {
            "api-key": apiKey
        },
        requestContentType: "application/json",
        body: JSON.stringify({
            model: String(options.model || "mimo-v2.5-tts"),
            messages: messages,
            audio: {
                format: "wav",
                voice: voiceId
            },
            stream: false
        }),
        responseType: "json",
        audioExtract: "choices[0].message.audio.data",
        audioEncoding: "base64",
        audioContentType: "audio/x-wav",
        timeout: Number(options.timeout || 60),
        retry: 1
    };
}
