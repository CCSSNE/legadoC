"use strict";

var model = require("lib/model.js");

// 存在性与权限由宿主 tools.call 统一校验；分级目录下记忆工具默认不在请求目录里，但按需可调用。
function call(id, args) {
    var result = host.call("tools.call", {moduleId: "memory", toolId: id, arguments: args});
    if (result.isError) throw new Error("记忆操作失败：" + JSON.stringify(result));
    return result.structuredContent;
}

function scope(input, config) {
    if (config.memory.scope === "global") return "global";
    if (config.memory.scope !== "book") throw new Error("未知记忆作用域：" + config.memory.scope);
    if (!input.reading.open || !input.reading.bookUrl) {
        var skip = new Error("记忆作用域为书籍，但当前没有打开阅读页；请选择全局作用域或打开书籍");
        skip.skipPassive = true;
        throw skip;
    }
    return input.reading.bookUrl;
}

// 被动路径不得致命：无书可依时记一笔可查的跳过日志并返回空，
// 本轮问答继续；真正的配置错误（未知作用域等）仍然抛出。
function passiveScope(input, config, phase) {
    try {
        return scope(input, config);
    } catch (error) {
        if (error.skipPassive) {
            host.call("log", {type: "memory." + phase + ".skip", reason: error.message, reading: input.reading});
            return null;
        }
        throw error;
    }
}

exports.recall = function(input, config) {
    if (!config.modules.memory) {
        host.call("emit", {type: "memory.recalled", value: {matches: [], skipped: "记忆模块未启用"}});
        return [];
    }
    var resolved = passiveScope(input, config, "recall");
    if (resolved === null) {
        host.call("emit", {type: "memory.recalled", value: {matches: [], skipped: "book作用域但无阅读页"}});
        return [];
    }
    var result = call("memory_search", {query: input.user, mode: "vector", scope: resolved});
    if (!(config.memory.recallCount >= 0)) throw new Error("recallCount 必须为非负整数");
    result.matches.sort(function(left, right) { return right.score - left.score; });
    var fraction = config.memory.contextFraction;
    if (!(fraction > 0 && fraction <= 1)) throw new Error("记忆 contextFraction 必须在 (0,1] 内");
    var budget = config.plugin.contextCharacters * fraction;
    var recalled = [];
    result.matches.forEach(function(item) {
        if (item.score >= config.memory.minimumScore && recalled.length < config.memory.recallCount && JSON.stringify(recalled.concat([item])).length <= budget) recalled.push(item);
    });
    host.call("emit", {type: "memory.recalled", value: {matches: recalled, candidates: result.matches.length, contextCharacters: budget}});
    return recalled;
};

exports.save = function(input, answer, config, reference) {
    if (!config.modules.memory || !config.memory.autoSave) return;
    var resolved = passiveScope(input, config, "save");
    if (resolved === null) return;
    var response = model.complete({model: reference.model, messages: [
        {role: "system", content: host.call("prompts.get", {key: config.plugin.memoryPromptKey})},
        {role: "user", content: JSON.stringify({question: input.user, answer: answer, reading: input.reading})}
    ], response_format: {type: "json_object"}}, reference.providerId, false);
    var extracted = JSON.parse(model.text(response.content));
    if (!Array.isArray(extracted.memories)) throw new Error("记忆提取结果缺少 memories 数组");
    extracted.memories.forEach(function(memory) {
        call("memory_add", {content: memory.content, type: memory.type, scope: resolved,
            source: "automatic", bookUrl: input.reading.bookUrl || "", chapterIndex: input.reading.chapterIndex, sourceMessageId: input.messageId});
    });
};
