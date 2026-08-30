// @name 脚本选项示例
// @schema 1
// @version 1.0.4
// @uuid script_options_example
// @author Legado
// @url http://localhost:8774
// @enabled false
// @cookieJar false
// @audioType audio/x-wav
// @defaultSpeed 50
// @defaultVolume 50
// @defaultPitch 50
// @description 展示 text/password/number/select/boolean/randomNumber 六类 options，并使用 MultiTTS 接口验证表单、发音人和试听。

function options() {
    return [
        { key: "baseUrl", label: "服务地址", type: "text", defaultValue: "http://localhost:8774" },
        { key: "token", label: "密码示例", type: "password", defaultValue: "" },
        {
            key: "deviceId",
            label: "设备 ID 示例",
            type: "randomNumber",
            digits: 13,
            allowLeadingZero: false,
            defaultValue: ""
        },
        { key: "timeout", label: "数字示例", type: "number", defaultValue: "30" },
        {
            key: "audioFormat",
            label: "选择示例",
            type: "select",
            values: [
                { label: "WAV 音频", value: "wav" },
                { label: "MP3 音频", value: "mp3" }
            ],
            defaultValue: "wav"
        },
        { key: "sendPitch", label: "布尔示例", type: "boolean", defaultValue: "true" }
    ];
}

function baseUrl(options) {
    return (options.baseUrl || "http://localhost:8774").replace(/\/+$/, "");
}

function voices(options, ctx) {
    return parseMultiTtsVoices(java.ajax(baseUrl(options) + "/voices"));
}

function parseMultiTtsVoices(body) {
    var json = JSON.parse(String(body || "{}"));
    var catalog = json && json.data && json.data.catalog ? json.data.catalog : {};
    var result = [];
    for (var provider in catalog) {
        if (!catalog.hasOwnProperty(provider)) {
            continue;
        }
        var list = catalog[provider] || [];
        for (var i = 0; i < list.length; i++) {
            var item = list[i];
            if (!item || !item.id || !item.name) {
                continue;
            }
            var tags = [];
            if (item.type) {
                tags.push(String(item.type));
            }
            if (provider) {
                tags.push(String(provider));
            }
            result.push({
                id: String(item.id),
                name: String(item.name),
                language: item.language || item.locale || "",
                gender: item.gender || "",
                style: item.style || "",
                tags: tags,
                extra: item
            });
        }
    }
    return result;
}

function synthesize(text, voice, params, options, ctx) {
    var query = [
        "volume=" + encodeURIComponent(params.volume),
        "speed=" + encodeURIComponent(params.speed),
        "voice=" + encodeURIComponent(voice.id || ""),
        "text=" + encodeURIComponent(text)
    ];
    if (String(options.sendPitch) !== "false") {
        query.push("pitch=" + encodeURIComponent(params.pitch));
    }
    return {
        url: baseUrl(options) + "/forward?" + query.join("&"),
        method: "GET",
        audioContentType: options.audioFormat === "mp3" ? "audio/mpeg" : "audio/x-wav",
        timeout: Number(options.timeout || 30),
        retry: 1
    };
}
