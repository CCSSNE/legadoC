"use strict";

var model = require("lib/model.js");

exports.build = function(history, input, config) {
    var result = [];
    var pending = Object.create(null);
    function closeUnknown() {
        Object.keys(pending).forEach(function(id) {
            var unknown = {isError: true, outcome: "unknown", error: "此前任务在此调用返回前中断。写入可能已经发生，不得自动重放；需要时先查询实际状态。"};
            result.push({role: "tool", tool_call_id: id, content: JSON.stringify(unknown)});
            host.call("log", {type: "context.unknown", tool_call_id: id, value: unknown});
        });
        pending = Object.create(null);
    }
    history.forEach(function(message) {
        if (message.role !== "tool") closeUnknown();
        if (message.role === "tool") delete pending[message.tool_call_id];
        (message.tool_calls || []).forEach(function(call) { pending[call.id] = true; });
        result.push(message);
    });
    closeUnknown();
    var system = host.call("prompts.get", {key: config.plugin.systemPromptKey});
    system += "\n本次提问的阅读快照（不是实时状态）：\n" + JSON.stringify(input.reading);
    var skillCards = [];
    host.call("skills.list").filter(function(skill) { return skill.enabled; }).forEach(function(skill) {
        system += "\nSkill " + skill.key + "（知识指导，不是工具）：\n" + skill.content;
        skillCards.push({key: skill.key, content: skill.content});
    });
    result.unshift({role: "system", content: system});
    var snapshot = input.reading || {};
    host.call("emit", {type: "prompt.context", value: {
        systemKey: config.plugin.systemPromptKey,
        systemChars: system.length,
        system: system,
        skills: skillCards,
        reading: {open: !!snapshot.open, bookName: snapshot.bookName || "", chapterTitle: snapshot.chapterTitle || ""}
    }});
    return result;
};

exports.compress = function(conversation, config, reference) {
    var budget = config.plugin.contextCharacters;
    if (!(budget > 0)) throw new Error("contextCharacters 必须为正整数");
    if (JSON.stringify(conversation).length <= budget) return conversation;
    if (!config.plugin.compressionEnabled) throw new Error("请求上下文超过模式配置容量，压缩已关闭；原始会话未删除");
    var lastUser = -1;
    conversation.forEach(function(message, index) { if (message.role === "user") lastUser = index; });
    if (lastUser <= 1) throw new Error("当前单回合已超过上下文容量，无法无损拆分工具往返；请增大容量或在模式里使用范围读取");
    var original = conversation.slice(1, lastUser);
    var summary = model.complete({model: reference.model, messages: [
        {role: "system", content: host.call("prompts.get", {key: config.plugin.compressionPromptKey})},
        {role: "user", content: JSON.stringify(original)}
    ]}, reference.providerId, false);
    var result = [conversation[0], {role: "system", content: "历史派生摘要（原始会话仍保留）：\n" + model.text(summary.content)}].concat(conversation.slice(lastUser));
    host.call("log", {type: "context.compression", original: original, derived: result});
    if (JSON.stringify(result).length > budget) throw new Error("摘要后仍超过上下文容量，请修改模式容量或压缩策略；未截断原始数据");
    return result;
};
