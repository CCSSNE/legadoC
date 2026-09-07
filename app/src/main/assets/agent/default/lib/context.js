"use strict";

var model = require("lib/model.js");

function estimateConversationTokens(conversation) {
    var total = 0;
    for (var index = 0; index < conversation.length; index++) {
        total += model.estimateTokens(JSON.stringify(conversation[index]));
    }
    return total;
}

// 历史裁剪：整段会话估算 token 超过 maxTokens 时，从最新往回按用户消息边界
// 保留到 trimToTokens，最早的历史整轮丢弃；开头 system 前缀与当前轮永不裁剪。
// 只影响本次请求组装，messages 原始历史保持完整（可回放）。
exports.trimHistory = function(conversation, config) {
    if (!(config.maxTokens > 0)) return {conversation: conversation, trimmed: false};
    if (!(config.trimToTokens > 0)) {
        host.call("log", {type: "context.trim.disabled", reason: "trimToTokens 未配置，历史裁剪未执行"});
        return {conversation: conversation, trimmed: false};
    }
    var total = estimateConversationTokens(conversation);
    if (total <= config.maxTokens) return {conversation: conversation, trimmed: false};
    var systemCount = 0;
    while (systemCount < conversation.length && conversation[systemCount].role === "system") systemCount++;
    var suffix = 0;
    var cut = -1;
    for (var index = conversation.length - 1; index > systemCount; index--) {
        suffix += model.estimateTokens(JSON.stringify(conversation[index]));
        if (conversation[index].role === "user" && suffix <= config.trimToTokens) { cut = index; break; }
    }
    if (cut < 0) {
        // 连当前轮都放不进保留预算：当前轮原样发出，由真实上下文上限直接暴露问题。
        for (var index = conversation.length - 1; index > systemCount; index--) {
            if (conversation[index].role === "user") { cut = index; break; }
        }
    }
    if (cut <= systemCount) return {conversation: conversation, trimmed: false};
    var result = conversation.slice(0, systemCount).concat(conversation.slice(cut));
    return {conversation: result, trimmed: true,
        beforeTokens: total, afterTokens: estimateConversationTokens(result),
        budgetTokens: config.maxTokens, keepTokens: config.trimToTokens};
};

// 工具结果裁剪：单条超过阈值（字符）时按模式保留头/尾，中间换成省略标记，
// 模型需要更多时用分页参数重读。原地替换请求表面的内容，messages 原始结果保持完整（可回放）。
exports.pruneToolOutputs = function(conversation, config) {
    if (!config.toolOutputTrimEnabled) return {pruned: 0, charsRemoved: 0};
    var threshold = config.toolOutputTrimChars;
    if (!(threshold > 0)) return {pruned: 0, charsRemoved: 0};
    var mode = config.toolOutputTrimMode;
    var head = mode === "head" ? threshold : (mode === "tail" ? 0 : Math.floor(threshold / 2));
    var tail = mode === "tail" ? threshold : threshold - head;
    var pruned = 0;
    var charsRemoved = 0;
    conversation.forEach(function(message) {
        if (message.role !== "tool" || typeof message.content !== "string") return;
        if (message.content.length <= threshold) return;
        var removed = message.content.length - head - tail;
        var parts = [];
        if (head > 0) parts.push(message.content.slice(0, head));
        parts.push("\n……[中间省略 " + removed + " 字；需要完整内容时用该工具的分页参数（cursor/offset/page）或缩小范围重新读取]……\n");
        if (tail > 0) parts.push(message.content.slice(message.content.length - tail));
        message.content = parts.join("");
        pruned++;
        charsRemoved += removed;
    });
    return {pruned: pruned, charsRemoved: charsRemoved};
};

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
