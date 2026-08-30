// @name MultiTTS·转发器
// @schema 1
// @version 1.0.3
// @uuid multitts_forwarder
// @author Legado
// @url http://localhost:8774
// @enabled false
// @cookieJar false
// @audioType audio/x-wav
// @defaultSpeed 50
// @defaultVolume 50
// @defaultPitch 50
// @description 连接本地 MultiTTS 服务，动态获取发音人并通过 /forward 合成音频。

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
        "pitch=" + encodeURIComponent(params.pitch),
        "voice=" + encodeURIComponent(voice.id || ""),
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
