// @name 阶跃星辰 StepAudio 2.5 TTS
// @schema 1
// @version 1.0.6
// @uuid stepfun_stepaudio_2_5_tts_v2_manual
// @author Legado NG local adapter
// @url https://api.stepfun.com/step_plan/v1/audio/speech
// @enabled false
// @cookieJar false
// @audioType audio/wav
// @defaultSpeed 50
// @defaultVolume 50
// @defaultPitch 50
// @concurrentRate 200
// @maxConcurrency 2
// @capabilities scene_context,performance_instruction
// @sampleText 前不见古人，后不见来者。念天地之悠悠，独怆然而涕下。
// @description Step Plan 接入。合成格式与采样率均可配置，默认使用非流式 48kHz WAV；场景写入全局 instruction，演员提示写入正文括号。

var STEPFUN_API_URL = "https://api.stepfun.com/step_plan/v1/audio/speech";
var STEPFUN_MODEL = "stepaudio-2.5-tts";

function stepVoice(name, id, gender, scenes) {
    return {
        id: id,
        name: name,
        language: "zh-CN",
        gender: gender,
        style: scenes.join("、"),
        tags: ["StepAudio"].concat(scenes),
        extra: {
            provider: "stepfun",
            model: STEPFUN_MODEL,
            recommended_scenes: scenes
        }
    };
}

var STEPFUN_VOICES = [
    stepVoice("Vibrant Youth", "vibrant-youth", "male", ["有声书", "视频配音"]),
    stepVoice("Lively Girl", "lively-girl", "female", ["有声书", "视频配音"]),
    stepVoice("Soft-spoken Gentleman", "soft-spoken-gentleman", "male", ["情感陪伴", "有声书"]),
    stepVoice("Magnetic-voiced Male", "magnetic-voiced-male", "male", ["有声书", "视频配音"]),
    stepVoice("自信男声", "zixinnansheng", "male", ["有声书", "情感陪伴", "教育与培训", "营销"]),
    stepVoice("气质温婉", "elegantgentle-female", "female", ["客服与业务办理", "口播", "教育与培训", "情感陪伴"]),
    stepVoice("活力轻快", "livelybreezy-female", "female", ["情感陪伴", "客服与业务办理", "教育与培训", "营销"]),
    stepVoice("温柔男声", "wenrounansheng", "male", ["口播", "情感陪伴", "客服与业务办理", "教育与培训"]),
    stepVoice("温柔公子", "wenrougongzi", "male", ["情感陪伴", "有声书"]),
    stepVoice("元气男声", "yuanqinansheng", "male", ["有声书", "口播", "客服与业务办理"]),
    stepVoice("经典女声", "jingdiannvsheng", "female", ["客服与业务办理", "情感陪伴"]),
    stepVoice("温柔熟女", "wenroushunv", "female", ["客服与业务办理", "口播", "教育与培训"]),
    stepVoice("甜美女声", "tianmeinvsheng", "female", ["情感陪伴", "客服与业务办理"]),
    stepVoice("清纯少女", "qingchunshaonv", "female", ["客服与业务办理", "语音助手"]),
    stepVoice("磁性男声", "cixingnansheng", "male", ["有声书", "情感陪伴"]),
    stepVoice("元气少女", "yuanqishaonv", "female", ["有声书", "情感陪伴", "语音助手"]),
    stepVoice("邻家姐姐", "linjiajiejie", "female", ["口播", "情感陪伴", "语音助手", "视频配音"]),
    stepVoice("正派青年", "zhengpaiqingnian", "male", ["营销", "有声书"]),
    stepVoice("青年大学生", "qingniandaxuesheng", "male", ["口播"]),
    stepVoice("播音男声", "boyinnansheng", "male", ["有声书", "口播"]),
    stepVoice("儒雅男士", "ruyananshi", "male", ["有声书", "情感陪伴", "口播", "语音助手"]),
    stepVoice("深沉男音", "shenchennanyin", "male", ["情感陪伴", "有声书"]),
    stepVoice("亲切女声", "qinqienvsheng", "female", ["口播"]),
    stepVoice("温柔女声", "wenrounvsheng", "female", ["有声书", "情感陪伴"]),
    stepVoice("机灵少女", "jilingshaonv", "female", ["语音助手", "口播"]),
    stepVoice("软萌女声", "ruanmengnvsheng", "female", ["情感陪伴", "语音助手", "视频配音"]),
    stepVoice("优雅女声", "youyanvsheng", "female", ["视频配音"]),
    stepVoice("冷艳御姐", "lengyanyujie", "female", ["视频配音"]),
    stepVoice("爽快姐姐", "shuangkuaijiejie", "female", ["口播"]),
    stepVoice("文静学姐", "wenjingxuejie", "female", ["口播"]),
    stepVoice("邻家妹妹", "linjiameimei", "female", ["视频配音", "口播", "语音助手"]),
    stepVoice("知性姐姐", "zhixingjiejie", "female", ["视频配音", "口播", "语音助手"]),
    stepVoice("爽快男声", "shuangkuainansheng", "male", ["客服与业务办理", "语音助手"]),
    stepVoice("干练女声", "ganliannvsheng", "female", ["客服与业务办理", "语音助手"]),
    stepVoice("亲和女声", "qinhenvsheng", "female", ["客服与业务办理", "语音助手"]),
    stepVoice("活力女声", "huolinvsheng", "female", ["客服与业务办理", "语音助手"])
];

function options() {
    return [
        { key: "apiKey", label: "Step Plan API Key", type: "password", defaultValue: "" },
        {
            key: "synthesisSpeed",
            label: "合成语速",
            type: "select",
            defaultValue: "1.0",
            values: ["0.8", "0.9", "1.0", "1.1", "1.2"]
        },
        {
            key: "outputFormat",
            label: "合成格式",
            type: "select",
            defaultValue: "wav",
            values: [
                { label: "WAV（无损，推荐）", value: "wav" },
                { label: "MP3（省流量，倍速可能失真）", value: "mp3" }
            ]
        },
        {
            key: "sampleRate",
            label: "采样率",
            type: "select",
            defaultValue: "48000",
            values: [
                { label: "48 kHz（推荐）", value: "48000" },
                { label: "24 kHz", value: "24000" }
            ]
        },
        { key: "timeout", label: "超时（秒）", type: "number", defaultValue: "120" }
    ];
}

function voices(options, ctx) {
    return STEPFUN_VOICES;
}

function trimText(value) {
    return String(value || "").replace(/^\s+|\s+$/g, "");
}

function collectSceneInstruction(ctx) {
    var synthesis = ctx && ctx.synthesis ? ctx.synthesis : null;
    var scene = synthesis && synthesis.scene ? synthesis.scene : null;
    if (!scene) return "";

    var result = [];
    var contextTexts = scene.context_texts;
    if (contextTexts && typeof contextTexts.length === "number") {
        for (var i = 0; i < contextTexts.length; i++) {
            var item = trimText(contextTexts[i]);
            if (item) result.push(item);
        }
    }
    if (result.length === 0) {
        var sceneText = trimText(scene.text);
        if (sceneText) result.push(sceneText);
    }
    return result.join("\n");
}

function outputFormat(options) {
    return trimText(options && options.outputFormat).toLowerCase() === "mp3" ? "mp3" : "wav";
}

function buildStepFunPayload(text, voice, options, ctx) {
    var content = trimText(text);
    if (!content) throw "合成文本不能为空";

    var synthesis = ctx && ctx.synthesis ? ctx.synthesis : null;
    var actorInstruction = synthesis ? trimText(synthesis.performance_instruction) : "";
    var input = actorInstruction ? "（" + actorInstruction + "）" + content : content;
    var payload = {
        model: STEPFUN_MODEL,
        voice: trimText(voice && voice.id),
        input: input,
        response_format: outputFormat(options),
        speed: Number(options && options.synthesisSpeed || 1.0),
        volume: 1.0,
        sample_rate: Number(options && options.sampleRate || 48000),
        text_normalization: "enhanced"
    };
    var sceneInstruction = collectSceneInstruction(ctx);
    if (sceneInstruction) payload.instruction = sceneInstruction;
    return payload;
}

function synthesize(text, voice, params, options, ctx) {
    var apiKey = trimText(options && options.apiKey);
    if (!apiKey) throw "请先填写阶跃星辰 API Key";
    var format = outputFormat(options);
    return {
        url: STEPFUN_API_URL,
        method: "POST",
        headers: {
            Authorization: "Bearer " + apiKey,
            "Content-Type": "application/json"
        },
        body: JSON.stringify(buildStepFunPayload(text, voice, options, ctx)),
        requestContentType: "application/json",
        audioContentType: format === "mp3" ? "audio/mpeg" : "audio/wav",
        timeout: Number(options && options.timeout || 120),
        retry: 1
    };
}
