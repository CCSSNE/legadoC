"use strict";

var model = require("lib/model.js");

exports.build = function(history, input, config, toolNames) {
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
        // 工具模型名升级后，历史里记录的旧哈希名改写为当前名，避免模型模仿历史旧名调用未加载工具。
        if (toolNames && (message.tool_calls || []).length) {
            message = JSON.parse(JSON.stringify(message));
            message.tool_calls.forEach(function(call) {
                var current = toolNames[call.function && call.function.name];
                if (current) call.function.name = current;
            });
        }
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

// 工具结果剪枝：单条超过阈值时保留头尾、中间换成省略标记，模型需要更多时用分页参数重读。
// 只替换请求表面的内容，messages 原始结果保持完整（可回放），与 dsh 的 tool-result-pruner 同一策略。
var PRUNE_THRESHOLD_CHARS = 8192;
var PRUNE_HEAD_CHARS = 4096;
var PRUNE_TAIL_CHARS = 1024;

exports.prune = function(conversation) {
    var pruned = 0;
    var charsRemoved = 0;
    var result = conversation.map(function(message) {
        if (message.role !== "tool" || typeof message.content !== "string") return message;
        if (message.content.length <= PRUNE_THRESHOLD_CHARS) return message;
        var removed = message.content.length - PRUNE_HEAD_CHARS - PRUNE_TAIL_CHARS;
        var content = message.content.slice(0, PRUNE_HEAD_CHARS)
            + "\n……[中间省略 " + removed + " 字；需要完整内容时用该工具的分页参数（cursor/offset/page）或缩小范围重新读取]……\n"
            + message.content.slice(message.content.length - PRUNE_TAIL_CHARS);
        pruned++;
        charsRemoved += removed;
        var copy = {};
        for (var key in message) copy[key] = message[key];
        copy.content = content;
        return copy;
    });
    if (pruned) host.call("log", {type: "context.prune", value: {pruned: pruned, charsRemoved: charsRemoved}});
    return pruned ? result : conversation;
};

// 历史硬保留预算：请求表面只保留最近 budgetTokens 的历史，按用户消息边界整轮裁剪，
// 当前轮永不裁剪；裁剪只影响本次请求组装，messages 原始历史保持完整（可回放）。
exports.retain = function(conversation, budgetTokens) {
    if (!(budgetTokens > 0) || conversation.length < 2) return conversation;
    var systemCount = 0;
    while (systemCount < conversation.length && conversation[systemCount].role === "system") systemCount++;
    var costs = [];
    var total = 0;
    for (var index = systemCount; index < conversation.length; index++) {
        var cost = model.estimateTokens(JSON.stringify(conversation[index]));
        costs.push(cost);
        total += cost;
    }
    if (total <= budgetTokens) return conversation;
    var suffix = 0;
    var cut = -1;
    for (var index = conversation.length - 1; index > systemCount; index--) {
        suffix += costs[index - systemCount];
        if (conversation[index].role === "user" && suffix <= budgetTokens) { cut = index; break; }
    }
    if (cut < 0) {
        // 连当前轮都超预算：只保留当前轮原样发出，由真实上下文上限直接暴露问题。
        for (var index = conversation.length - 1; index > systemCount; index--) {
            if (conversation[index].role === "user") { cut = index; break; }
        }
    }
    if (cut <= systemCount) return conversation;
    var dropped = 0;
    for (var index = systemCount; index < cut; index++) dropped += costs[index - systemCount];
    host.call("log", {type: "context.retain", value: {
        budgetTokens: budgetTokens, estimatedTokensBefore: total, estimatedTokensAfter: total - dropped,
        messagesBefore: conversation.length - systemCount, messagesAfter: conversation.length - cut, droppedMessages: cut - systemCount
    }});
    return conversation.slice(0, systemCount).concat(conversation.slice(cut));
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
