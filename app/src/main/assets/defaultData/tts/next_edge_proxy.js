// @name Next Edge TTS
// @schema 1
// @version 1.0.8
// @uuid next_edge_proxy
// @author Legado
// @url http://5.45.99.149:8075/tts
// @enabled true
// @cookieJar false
// @audioType audio/mpeg
// @defaultSpeed 50
// @defaultVolume 50
// @defaultPitch 50
// @sampleText 前不见古人，后不见来者。念天地之悠悠，独怆然而涕下。
// @capabilities style_tags,emotion
// @description 第三方 Edge TTS 中转调试模板，发音人画像和 styles 参考 Edge VoiceTag 与 Azure Speech 文档。

var STYLE_NAMES = {
    "advertisement-upbeat": "广告活力",
    "affectionate": "深情",
    "angry": "愤怒",
    "anxious": "焦虑",
    "assassin": "刺客",
    "assistant": "助手",
    "calm": "平静",
    "captain": "队长",
    "cavalier": "骑士",
    "chat": "聊天",
    "chat-casual": "随意聊天",
    "cheerful": "欢快",
    "comforting": "安慰",
    "complaining": "抱怨",
    "curious": "好奇",
    "customer-service": "客户服务",
    "customerservice": "客户服务",
    "cute": "可爱",
    "debating": "辩论",
    "depressed": "沮丧",
    "disappointed": "失望",
    "disgruntled": "不满",
    "documentary-narration": "纪录片旁白",
    "empathetic": "共情",
    "embarrassed": "尴尬",
    "encouraging": "鼓励",
    "envious": "羡慕",
    "excited": "兴奋",
    "fearful": "恐惧",
    "friendly": "友好",
    "game-narrator": "游戏旁白",
    "gentle": "温柔",
    "geomancer": "方士",
    "guilty": "愧疚",
    "live-commercial": "直播带货",
    "livecommercial": "直播带货",
    "lonely": "孤独",
    "lyrical": "抒情",
    "narration-professional": "专业旁白",
    "narration-relaxed": "舒缓旁白",
    "newscast": "新闻播报",
    "newscast-casual": "轻松新闻",
    "news": "新闻",
    "poet": "诗人",
    "poetry-reading": "诗歌朗诵",
    "prince": "王子",
    "sad": "悲伤",
    "sentimental": "感伤",
    "serious": "严肃",
    "shy": "害羞",
    "sorry": "歉意",
    "sports-commentary": "体育解说",
    "sports-commentary-excited": "兴奋体育解说",
    "story": "故事",
    "story-telling": "讲故事",
    "strict": "严厉",
    "surprised": "惊讶",
    "tired": "疲惫",
    "voice-assistant": "语音助手",
    "whispering": "耳语"
};

var EXPRESSIVE_STYLE_ALIASES = [
    { style: "angry", terms: ["angry", "愤怒", "生气", "恼怒", "暴怒"] },
    { style: "anxious", terms: ["anxious", "焦虑", "紧张", "不安"] },
    { style: "calm", terms: ["calm", "平静", "克制", "冷静", "沉着"] },
    { style: "cheerful", terms: ["cheerful", "欢快", "开心", "喜悦", "活泼"] },
    { style: "comforting", terms: ["comforting", "安慰", "宽慰"] },
    { style: "curious", terms: ["curious", "好奇", "疑惑"] },
    { style: "depressed", terms: ["depressed", "沮丧", "低落", "压抑"] },
    { style: "disappointed", terms: ["disappointed", "失望"] },
    { style: "embarrassed", terms: ["embarrassed", "尴尬", "窘迫"] },
    { style: "empathetic", terms: ["empathetic", "共情", "同情"] },
    { style: "excited", terms: ["excited", "兴奋", "激动", "高昂"] },
    { style: "fearful", terms: ["fearful", "恐惧", "害怕", "惊恐"] },
    { style: "gentle", terms: ["gentle", "温柔", "柔和", "轻柔"] },
    { style: "sad", terms: ["sad", "悲伤", "难过", "哀伤"] },
    { style: "serious", terms: ["serious", "严肃", "郑重", "庄重"] },
    { style: "shy", terms: ["shy", "害羞", "羞涩"] },
    { style: "sorry", terms: ["sorry", "歉意", "抱歉", "愧疚"] },
    { style: "surprised", terms: ["surprised", "惊讶", "震惊", "意外"] },
    { style: "whispering", terms: ["whispering", "耳语", "低语", "悄声"] },
    { style: "newscast", terms: ["newscast", "新闻播报", "播报"] },
    { style: "poetry-reading", terms: ["poetry-reading", "诗歌朗诵", "朗诵"] },
    { style: "story", terms: ["story", "故事", "讲故事", "叙事"] },
    { style: "story-telling", terms: ["story-telling", "故事", "讲故事", "叙事"] }
];

var API_OPTIONS = [
    { label: "5.45.99.149", value: "http://5.45.99.149:8075/tts" },
    { label: "146.56.188.115", value: "http://146.56.188.115:8080/tts" }
];

var VOICES = [
    {
        name: "晓晓",
        id: "zh-CN-XiaoxiaoNeural",
        gender: "female",
        profile: "少女感-温柔旁白",
        categories: ["News", "Novel"],
        personalities: ["Warm"],
        styles: ["affectionate", "angry", "assistant", "calm", "chat", "chat-casual", "cheerful", "customerservice", "disgruntled", "excited", "fearful", "friendly", "gentle", "lyrical", "newscast", "poetry-reading", "sad", "serious", "sorry", "whispering"],
        roles: [],
        roleHints: ["温柔旁白", "年轻女主", "新闻播报"]
    },
    {
        name: "晓亦",
        id: "zh-CN-XiaoyiNeural",
        gender: "female",
        profile: "少女-活泼甜亮",
        categories: ["Cartoon", "Novel"],
        personalities: ["Lively"],
        styles: ["affectionate", "angry", "cheerful", "disgruntled", "embarrassed", "fearful", "gentle", "sad", "serious"],
        roles: [],
        roleHints: ["少女", "校园女主", "活泼配角"]
    },
    {
        name: "晓辰",
        id: "zh-CN-XiaochenNeural",
        gender: "female",
        profile: "女青年-自然柔和",
        categories: [],
        personalities: [],
        styles: ["livecommercial"],
        roles: [],
        roleHints: ["少女", "温柔女主", "情绪对白"]
    },
    {
        name: "晓涵",
        id: "zh-CN-XiaohanNeural",
        gender: "female",
        profile: "少女-高亮温柔",
        categories: [],
        personalities: [],
        styles: ["affectionate", "angry", "calm", "cheerful", "disgruntled", "embarrassed", "fearful", "gentle", "sad", "serious"],
        roles: [],
        roleHints: ["女主", "女青年", "温柔配角"]
    },
    {
        name: "晓梦",
        id: "zh-CN-XiaomengNeural",
        gender: "female",
        profile: "少女-轻柔聊天",
        categories: [],
        personalities: [],
        styles: ["chat"],
        roles: [],
        roleHints: ["少女", "轻柔聊天", "自然对话"]
    },
    {
        name: "晓墨",
        id: "zh-CN-XiaomoNeural",
        gender: "female",
        profile: "少女-多角色温柔",
        categories: [],
        personalities: [],
        styles: ["affectionate", "angry", "calm", "cheerful", "depressed", "disgruntled", "embarrassed", "envious", "fearful", "gentle", "sad", "serious"],
        roles: ["Boy", "Girl", "OlderAdultFemale", "OlderAdultMale", "SeniorFemale", "SeniorMale", "YoungAdultFemale", "YoungAdultMale"],
        roleHints: ["多角色", "少女", "年轻女主"]
    },
    { name: "晓秋", id: "zh-CN-XiaoqiuNeural", gender: "female", profile: "成熟女声-高亮硬朗", categories: [], personalities: [], styles: [], roles: [], roleHints: ["母亲", "老师", "成熟女性", "旁白"] },
    { name: "晓柔", id: "zh-CN-XiaorouNeural", gender: "female", profile: "少女-清甜", categories: [], personalities: [], styles: [], roles: [], roleHints: ["少女", "校园女主", "活泼配角"] },
    { name: "晓睿", id: "zh-CN-XiaoruiNeural", gender: "female", profile: "少女-冷亮情绪", categories: [], personalities: [], styles: ["angry", "calm", "fearful", "sad"], roles: [], roleHints: ["少女", "冷静角色", "情绪对白"] },
    { name: "晓双", id: "zh-CN-XiaoshuangNeural", gender: "female", profile: "女童/少女-稚嫩清亮", categories: [], personalities: [], styles: ["chat"], roles: [], roleHints: ["女童", "少女", "轻松聊天"] },
    { name: "晓颜", id: "zh-CN-XiaoyanNeural", gender: "female", profile: "女青年-自然柔和", categories: [], personalities: [], styles: [], roles: [], roleHints: ["女主", "女青年", "温柔配角"] },
    { name: "晓悠", id: "zh-CN-XiaoyouNeural", gender: "female", profile: "少女-高亮稚嫩", categories: [], personalities: [], styles: [], roles: [], roleHints: ["少女", "活泼配角", "清亮旁白"] },
    { name: "晓甄", id: "zh-CN-XiaozhenNeural", gender: "female", profile: "女青年-清亮情绪", categories: [], personalities: [], styles: ["angry", "cheerful", "disgruntled", "fearful", "sad", "serious"], roles: [], roleHints: ["女青年", "情绪对白", "剧情配角"] },
    {
        name: "云希",
        id: "zh-CN-YunxiNeural",
        gender: "male",
        profile: "青年男声-阳光叙事",
        categories: ["Novel"],
        personalities: ["Lively", "Sunshine"],
        styles: ["angry", "assistant", "chat", "cheerful", "depressed", "disgruntled", "embarrassed", "fearful", "narration-relaxed", "newscast", "sad", "serious"],
        roles: ["Boy", "Narrator", "YoungAdultMale"],
        roleHints: ["年轻男主", "少年", "旁白"]
    },
    {
        name: "云间",
        id: "zh-CN-YunjianNeural",
        gender: "male",
        profile: "低沉男声-热血解说",
        categories: ["Sports", "Novel"],
        personalities: ["Passion"],
        styles: ["angry", "cheerful", "depressed", "disgruntled", "documentary-narration", "narration-relaxed", "sad", "serious", "sports-commentary", "sports-commentary-excited"],
        roles: [],
        roleHints: ["热血男配", "战斗旁白", "赛事解说"]
    },
    { name: "云扬", id: "zh-CN-YunyangNeural", gender: "male", profile: "青年男声-专业播报", categories: ["News"], personalities: ["Professional", "Reliable"], styles: ["customerservice", "narration-professional", "newscast-casual"], roles: [], roleHints: ["新闻播报", "专业旁白", "官方说明"] },
    { name: "云枫", id: "zh-CN-YunfengNeural", gender: "male", profile: "青年男声-清朗情绪", categories: [], personalities: [], styles: ["angry", "cheerful", "depressed", "disgruntled", "fearful", "sad", "serious"], roles: [], roleHints: ["男主", "书生", "清朗旁白"] },
    { name: "云皓", id: "zh-CN-YunhaoNeural", gender: "male", profile: "低沉男声-活力广告", categories: [], personalities: [], styles: ["advertisement-upbeat"], roles: [], roleHints: ["成熟男声", "活力说明", "广告旁白"] },
    { name: "云杰", id: "zh-CN-YunjieNeural", gender: "male", profile: "少年男声-温和", categories: [], personalities: [], styles: [], roles: [], roleHints: ["少年", "年轻男主", "同学"] },
    { name: "云夏", id: "zh-CN-YunxiaNeural", gender: "male", profile: "男童/少年-高亮稚嫩", categories: ["Cartoon", "Novel"], personalities: ["Cute"], styles: ["angry", "calm", "cheerful", "fearful", "sad"], roles: [], roleHints: ["男童", "少年", "活泼男配"] },
    { name: "云野", id: "zh-CN-YunyeNeural", gender: "male", profile: "低沉大叔-多角色", categories: [], personalities: [], styles: ["angry", "calm", "cheerful", "disgruntled", "embarrassed", "fearful", "sad", "serious"], roles: ["Boy", "Girl", "OlderAdultFemale", "OlderAdultMale", "SeniorFemale", "SeniorMale", "YoungAdultFemale", "YoungAdultMale"], roleHints: ["大叔", "父亲", "反派", "厚重旁白"] },
    { name: "云泽", id: "zh-CN-YunzeNeural", gender: "male", profile: "低沉长辈-纪录片", categories: [], personalities: [], styles: ["angry", "calm", "cheerful", "depressed", "disgruntled", "documentary-narration", "fearful", "sad", "serious"], roles: ["OlderAdultMale", "SeniorMale"], roleHints: ["成熟男性", "长辈", "纪录片旁白"] },
    { name: "晓晓DIA", id: "zh-CN-XiaoxiaoDialectsNeural", gender: "female", profile: "女青年-方言清亮", categories: [], personalities: [], styles: [], roles: [], roleHints: ["方言", "女青年", "旁白"] },
    { name: "晓晓MT", id: "zh-CN-XiaoxiaoMultilingualNeural", gender: "female", profile: "女青年-温柔多语", categories: [], personalities: [], styles: ["affectionate", "cheerful", "empathetic", "excited", "poetry-reading", "sorry", "story"], roles: [], roleHints: ["多语言", "温柔女主", "故事旁白"] },
    { name: "晓宇MT", id: "zh-CN-XiaoyuMultilingualNeural", gender: "female", profile: "少女-柔亮多语", categories: [], personalities: [], styles: [], roles: [], roleHints: ["多语言", "少女", "配角"] },
    { name: "云晓MT", id: "zh-CN-YunxiaoMultilingualNeural", gender: "male", profile: "青年男声-温和多语", categories: [], personalities: [], styles: [], roles: [], roleHints: ["多语言", "男青年", "旁白"] },
    { name: "云逸MT", id: "zh-CN-YunyiMultilingualNeural", gender: "male", profile: "青年男声-清朗多语", categories: [], personalities: [], styles: [], roles: [], roleHints: ["多语言", "男青年", "配角"] },
    { name: "云帆MT", id: "zh-CN-YunfanMultilingualNeural", gender: "male", profile: "青年男声-明亮多语", categories: [], personalities: [], styles: [], roles: [], roleHints: ["多语言", "男青年", "旁白"] },
    { name: "晓辰HD", id: "zh-CN-Xiaochen:DragonHDLatestNeural", gender: "female", profile: "高清女声-自然柔和", categories: [], personalities: [], styles: [], roles: [], hd: true, roleHints: ["女青年", "HD", "高质量旁白"] },
    { name: "云帆HD", id: "zh-CN-Yunfan:DragonHDLatestNeural", gender: "male", profile: "高清男声-清朗", categories: [], personalities: [], styles: [], roles: [], hd: true, roleHints: ["男青年", "HD", "高质量旁白"] },
    { name: "晓辰FHD", id: "zh-CN-Xiaochen:DragonHDFlashLatestNeural", gender: "female", profile: "高清少女-明亮情绪", categories: [], personalities: [], styles: ["cheerful", "debating", "empathetic", "live-commercial", "poetry-reading", "sad", "sorry"], roles: [], hd: true, roleHints: ["女青年", "直播", "诗歌朗诵"] },
    { name: "晓晓FHD", id: "zh-CN-Xiaoxiao:DragonHDFlashLatestNeural", gender: "female", profile: "高清少女-助手情绪", categories: [], personalities: [], styles: ["angry", "chat", "cheerful", "customer-service", "excited", "fearful", "sad", "voice-assistant"], roles: [], hd: true, roleHints: ["少女", "助手", "情绪对白"] },
    { name: "潇潇FHD", id: "zh-CN-Xiaoxiao2:DragonHDFlashLatestNeural", gender: "female", profile: "高清女青年-细腻情绪", categories: [], personalities: [], styles: ["affectionate", "angry", "anxious", "cheerful", "curious", "disappointed", "empathetic", "encouraging", "excited", "fearful", "guilty", "lonely", "poetry-reading", "sad", "sentimental", "sorry", "story", "surprised", "tired", "whispering"], roles: [], hd: true, roleHints: ["女青年", "情绪对白", "故事旁白"] },
    { name: "云萧FHD", id: "zh-CN-Yunxiao:DragonHDFlashLatestNeural", gender: "male", profile: "高清男声-清朗", categories: [], personalities: [], styles: [], roles: [], hd: true, roleHints: ["男青年", "高质量旁白", "配角"] },
    { name: "云野FHD", id: "zh-CN-Yunye:DragonHDFlashLatestNeural", gender: "male", profile: "高清大叔-低沉", categories: [], personalities: [], styles: [], roles: [], hd: true, roleHints: ["男中年", "沉稳旁白", "反派"] },
    { name: "云逸FHD", id: "zh-CN-Yunyi:DragonHDFlashLatestNeural", gender: "male", profile: "高清男声-角色演绎", categories: [], personalities: [], styles: ["assassin", "captain", "cavalier", "game-narrator", "geomancer", "poet", "prince"], roles: [], hd: true, roleHints: ["游戏角色", "王子", "诗人"] },
    { name: "晓宸_繁", id: "zh-TW-HsiaoChenNeural", gender: "female", profile: "台配女青年-自然亲和", categories: ["General"], personalities: ["Friendly", "Positive"], styles: [], roles: [], roleHints: ["繁中女声", "女青年", "旁白"] },
    { name: "晓语_繁", id: "zh-TW-HsiaoYuNeural", gender: "female", profile: "台配成熟女声-明亮", categories: ["General"], personalities: ["Friendly", "Positive"], styles: [], roles: [], roleHints: ["繁中女声", "成熟女性", "旁白"] },
    { name: "云喆_繁", id: "zh-TW-YunJheNeural", gender: "male", profile: "台配男声-温和低沉", categories: ["General"], personalities: ["Friendly", "Positive"], styles: [], roles: [], roleHints: ["繁中男声", "成熟男性", "旁白"] }
];

function options() {
    return [
        {
            key: "api",
            label: "接口",
            type: "select",
            defaultValue: "http://5.45.99.149:8075/tts",
            values: API_OPTIONS
        },
        { key: "timeout", label: "超时秒数", type: "number", defaultValue: "30" }
    ];
}

function voices(options, ctx) {
    var result = [];
    for (var i = 0; i < VOICES.length; i++) {
        var item = VOICES[i];
        var tags = ["Edge", item.profile];
        appendAll(tags, item.categories);
        appendAll(tags, item.personalities);
        if (item.hd) {
            tags.push("HD");
        }
        result.push({
            id: item.id,
            name: item.name,
            language: languageOf(item.id),
            gender: item.gender,
            style: item.styles && item.styles.length ? item.styles.length + " 种风格" : "",
            tags: tags,
            extra: {
                provider: "next-edge-proxy",
                profile: item.profile,
                categories: item.categories || [],
                personalities: item.personalities || [],
                azure_styles: item.styles || [],
                roles: item.roles || [],
                role_hints: item.roleHints || [],
                styles: styleOptions(item.styles || [])
            }
        });
    }
    return result;
}

function styleOptions(styles) {
    var result = [];
    for (var i = 0; i < styles.length; i++) {
        var id = String(styles[i]);
        result.push({
            id: id,
            name: STYLE_NAMES[id] || id,
            value: id
        });
    }
    return result;
}

function appendAll(target, values) {
    values = values || [];
    for (var i = 0; i < values.length; i++) {
        target.push(String(values[i]));
    }
}

function synthesize(text, voice, params, options, ctx) {
    var api = String(options.api || "http://5.45.99.149:8075/tts").replace(/\/+$/, "");
    var voiceId = String((voice && voice.id) || "zh-CN-YunxiNeural");
    var style = selectedStyleValue(voice) || automaticStyleValue(voice, ctx);
    var rate = clamp((Number(params.speed || 50) - 50) * 2, -100, 100);
    var pitch = clamp((Number(params.pitch || 50) - 50) * 2, -100, 100);
    var query = [
        "t=" + encodeURIComponent(text),
        "v=" + encodeURIComponent(voiceId),
        "r=" + encodeURIComponent(rate),
        "p=" + encodeURIComponent(pitch),
        "s=" + encodeURIComponent(style),
        "api_key="
    ].join("&");
    return {
        url: api + "?" + query,
        method: "GET",
        audioContentType: "audio/mpeg",
        timeout: Number(options.timeout || 30),
        retry: 1
    };
}

function selectedStyleValue(voice) {
    if (voice && voice.selected_style && voice.selected_style.value != null) {
        return String(voice.selected_style.value);
    }
    if (voice && voice.style_value != null) {
        return String(voice.style_value);
    }
    return "";
}

function automaticStyleValue(voice, ctx) {
    var supported = voice && voice.extra && voice.extra.azure_styles
        ? voice.extra.azure_styles
        : [];
    if (!supported.length) {
        return "";
    }
    var expressive = ctx && ctx.synthesis && ctx.synthesis.expressive
        ? ctx.synthesis.expressive
        : null;
    if (!expressive) {
        return "";
    }
    var concepts = [];
    if (expressive.emotion) {
        concepts.push(String(expressive.emotion));
    }
    var styleConcepts = expressive.style_concepts || [];
    for (var i = 0; i < styleConcepts.length; i++) {
        concepts.push(String(styleConcepts[i]));
    }
    for (var j = 0; j < concepts.length; j++) {
        var matched = styleForConcept(concepts[j], supported);
        if (matched) {
            return matched;
        }
    }
    return "";
}

function styleForConcept(concept, supported) {
    var normalized = String(concept || "").toLowerCase().replace(/^\s+|\s+$/g, "");
    if (!normalized) {
        return "";
    }
    if (containsStyle(supported, normalized)) {
        return normalized;
    }
    for (var i = 0; i < EXPRESSIVE_STYLE_ALIASES.length; i++) {
        var item = EXPRESSIVE_STYLE_ALIASES[i];
        if (!containsStyle(supported, item.style)) {
            continue;
        }
        for (var j = 0; j < item.terms.length; j++) {
            if (normalized.indexOf(item.terms[j]) >= 0) {
                return item.style;
            }
        }
    }
    return "";
}

function containsStyle(styles, expected) {
    for (var i = 0; i < styles.length; i++) {
        if (String(styles[i]).toLowerCase() === expected) {
            return true;
        }
    }
    return false;
}

function languageOf(voiceId) {
    if (String(voiceId).indexOf("zh-TW-") === 0) {
        return "zh-TW";
    }
    return "zh-CN";
}

function clamp(value, min, max) {
    value = Number(value);
    if (isNaN(value)) {
        return 0;
    }
    return Math.max(min, Math.min(max, Math.round(value)));
}
