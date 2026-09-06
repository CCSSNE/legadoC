"use strict";

var model = require("lib/model.js");

function call(catalog, id, args) {
    var tool = catalog.filter(function(item) { return item.moduleId === "memory" && item.toolId === id; })[0];
    if (!tool) throw new Error("记忆工具未启用：" + id);
    var result = host.call("tools.call", {moduleId: "memory", toolId: id, arguments: args});
    if (result.isError) throw new Error("记忆操作失败：" + JSON.stringify(result));
    return result.structuredContent;
}

function scope(input, config) {
    if (config.memory.scope === "global") return "global";
    if (config.memory.scope !== "book") throw new Error("未知记忆作用域：" + config.memory.scope);
    if (!input.reading.open || !input.reading.bookUrl) throw new Error("记忆作用域为书籍，但当前没有打开阅读页；请选择全局作用域或打开书籍");
    return input.reading.bookUrl;
}

exports.recall = function(input, config, catalog) {
    if (!config.modules.memory) return [];
    var result = call(catalog, "memory_search", {query: input.user, mode: "vector", scope: scope(input, config)});
    if (!(config.memory.recallCount >= 0)) throw new Error("recallCount 必须为非负整数");
    result.matches.sort(function(left, right) { return right.score - left.score; });
    var fraction = config.memory.contextFraction;
    if (!(fraction > 0 && fraction <= 1)) throw new Error("记忆 contextFraction 必须在 (0,1] 内");
    var budget = config.plugin.contextCharacters * fraction;
    var recalled = [];
    result.matches.forEach(function(item) {
        if (item.score >= config.memory.minimumScore && recalled.length < config.memory.recallCount && JSON.stringify(recalled.concat([item])).length <= budget) recalled.push(item);
    });
    host.call("log", {type: "memory.recall", candidates: result.matches, selected: recalled, contextCharacters: budget});
    return recalled;
};

exports.save = function(input, answer, config, catalog, reference) {
    if (!config.modules.memory || !config.memory.autoSave) return;
    var response = model.complete({model: reference.model, messages: [
        {role: "system", content: host.call("prompts.get", {key: config.plugin.memoryPromptKey})},
        {role: "user", content: JSON.stringify({question: input.user, answer: answer, reading: input.reading})}
    ], response_format: {type: "json_object"}}, reference.providerId, false);
    var extracted = JSON.parse(model.text(response.content));
    if (!Array.isArray(extracted.memories)) throw new Error("记忆提取结果缺少 memories 数组");
    extracted.memories.forEach(function(memory) {
        call(catalog, "memory_add", {content: memory.content, type: memory.type, scope: scope(input, config),
            source: "automatic", bookUrl: input.reading.bookUrl || "", chapterIndex: input.reading.chapterIndex, sourceMessageId: input.messageId});
    });
};
