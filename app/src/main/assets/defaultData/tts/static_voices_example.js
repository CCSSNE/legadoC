// @name 内置发音人示例
// @schema 1
// @version 1.0.2
// @uuid script_static_voices_example
// @author Legado
// @url http://localhost:8774
// @enabled false
// @cookieJar false
// @audioType audio/x-wav
// @defaultSpeed 50
// @defaultVolume 50
// @defaultPitch 50
// @description 演示没有远端发音人接口时，如何在 voices(options, ctx) 中直接返回静态发音人数组。

function options() {
    return [
        { key: "baseUrl", label: "服务地址", type: "text", defaultValue: "http://localhost:8774" },
        { key: "timeout", label: "超时秒数", type: "number", defaultValue: "30" }
    ];
}

function baseUrl(options) {
    return (options.baseUrl || "http://localhost:8774").replace(/\/+$/, "");
}

function voices(options, ctx) {
    return [
        {
            id: "microsoft_zh-CN-XiaoxiaoNeural",
            name: "晓晓",
            language: "zh-CN",
            gender: "female",
            tags: ["static", "microsoft"],
            sample_text: "前不见古人，后不见来者。念天地之悠悠，独怆然而涕下。",
            extra: {
                provider: "microsoft",
                shortName: "zh-CN-XiaoxiaoNeural"
            }
        },
        {
            id: "microsoft_zh-CN-YunxiNeural",
            name: "云希",
            language: "zh-CN",
            gender: "male",
            tags: ["static", "microsoft"],
            sample_text: "前不见古人，后不见来者。念天地之悠悠，独怆然而涕下。",
            extra: {
                provider: "microsoft",
                shortName: "zh-CN-YunxiNeural"
            }
        }
    ];
}

function synthesize(text, voice, params, options, ctx) {
    var voiceId = voice.extra && voice.extra.shortName || voice.id || "";
    var query = [
        "volume=" + encodeURIComponent(params.volume),
        "speed=" + encodeURIComponent(params.speed),
        "pitch=" + encodeURIComponent(params.pitch),
        "voice=" + encodeURIComponent(voiceId),
        "text=" + encodeURIComponent(text)
    ].join("&");
    return {
        url: baseUrl(options) + "/forward?" + query,
        method: "GET",
        audioContentType: "audio/x-wav",
        timeout: Number(options.timeout || 30),
        retry: 1
    };
}
