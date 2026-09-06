"use strict";

exports.text = function(content) {
    if (content === null || content === undefined) return "";
    if (typeof content === "string") return content;
    if (Array.isArray(content)) return content.map(function(part) { return part.text || ""; }).join("");
    throw new Error("模型 content 类型不受支持");
};

exports.complete = function(body, providerId, display) {
    body.stream = true;
    if (body.tools && !body.tools.length) delete body.tools;
    var content = "";
    var reasoning = "";
    var calls = {};
    var finished = false;
    var finishReason = null;
    var assistant = null;
    var response = host.call("model.request", {body: body, providerId: providerId}, function(event, data) {
        if (data === "[DONE]") { finished = true; return true; }
        var chunk = JSON.parse(data);
        if (chunk.error) throw new Error(JSON.stringify(chunk.error));
        if (!chunk.choices || !chunk.choices.length) return false;
        var choice = chunk.choices[0];
        if (choice.finish_reason) finishReason = choice.finish_reason;
        var delta = choice.delta || choice.message || {};
        if (delta.content) content += exports.text(delta.content);
        if (delta.reasoning_content) reasoning += delta.reasoning_content;
        (delta.tool_calls || []).forEach(function(part) {
            if (typeof part.index !== "number") throw new Error("流式 tool_calls 缺少 index");
            var current = calls[part.index];
            if (!current) current = calls[part.index] = {id: "", type: "function", function: {name: "", arguments: ""}};
            if (part.id) current.id = part.id;
            if (part.function) {
                if (part.function.name) current.function.name += part.function.name;
                if (part.function.arguments) current.function.arguments += part.function.arguments;
            }
        });
        if (display) {
            if (content) host.call("emit", {type: "output", value: {text: content}});
            else if (reasoning) host.call("emit", {type: "thinking", value: {text: reasoning}});
        }
        return false;
    });
    if (response.status < 200 || response.status >= 300) throw new Error("模型 HTTP " + response.status + " / " + response.requestId + ": " + response.body);
    if (!response.stream) {
        var complete = JSON.parse(response.body);
        if (complete.error) throw new Error(JSON.stringify(complete.error));
        if (!complete.choices || !complete.choices.length) throw new Error("模型响应缺少 choices");
        assistant = complete.choices[0].message;
        finishReason = complete.choices[0].finish_reason;
        if (!assistant) throw new Error("模型响应缺少 assistant message");
        assistant.role = "assistant";
        if (display) host.call("emit", {type: "output", value: {text: exports.text(assistant.content)}});
    } else {
        if (!finished && !finishReason) throw new Error("模型流意外中断，未收到完成标记；未自动重发");
        assistant = {role: "assistant", content: content || null};
        var toolCalls = Object.keys(calls).sort(function(left, right) { return Number(left) - Number(right); }).map(function(key) { return calls[key]; });
        if (toolCalls.length) assistant.tool_calls = toolCalls;
        if (reasoning) assistant.reasoning_content = reasoning;
    }
    if (finishReason === "length" || finishReason === "content_filter") throw new Error("模型未完成输出：" + finishReason + "，请修改模式上下文/请求参数");
    var identifiers = Object.create(null);
    (assistant.tool_calls || []).forEach(function(call) {
        if (!call.id || !call.function || !call.function.name) throw new Error("模型工具调用缺少 id/function/name");
        if (typeof call.function.arguments !== "string") throw new Error("工具 arguments 必须为 JSON 字符串");
        if (identifiers[call.id]) throw new Error("模型工具调用 ID 重复：" + call.id);
        if (call.type && call.type !== "function") throw new Error("未支持的工具调用类型：" + call.type);
        identifiers[call.id] = true;
        JSON.parse(call.function.arguments);
    });
    return assistant;
};
