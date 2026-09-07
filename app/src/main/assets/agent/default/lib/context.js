"use strict";

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
