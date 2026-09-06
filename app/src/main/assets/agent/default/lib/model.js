"use strict";

exports.text = function(content) {
    if (content === null || content === undefined) return "";
    if (typeof content === "string") return content;
    if (Array.isArray(content)) return content.map(function(part) { return part.text || ""; }).join("");
    throw new Error("模型 content 类型不受支持");
};

function estimateTokens(text) {
    return Math.ceil((text || "").length / 4);
}

// 用量事件只做观测：emit 失败不得影响本次模型调用本身。
function emitModelUsage(value) {
    try {
        host.call("emit", {type: "model.usage", value: value});
    } catch (ignore) {}
}

function readModelUsage(raw, body, content, reasoning, startedAt, firstTokenAt, display) {
    var elapsed = Date.now() - startedAt;
    var ttft = firstTokenAt ? firstTokenAt - startedAt : elapsed;
    var value = {promptTokens: 0, completionTokens: 0, cachedTokens: 0, reasoningTokens: 0,
        elapsedMs: elapsed, ttftMs: ttft < 0 ? 0 : ttft, display: !!display, estimated: false};
    if (raw && typeof raw === "object") {
        value.promptTokens = raw.prompt_tokens !== undefined ? raw.prompt_tokens : (raw.input_tokens !== undefined ? raw.input_tokens : 0);
        value.completionTokens = raw.completion_tokens !== undefined ? raw.completion_tokens : (raw.output_tokens !== undefined ? raw.output_tokens : 0);
        var promptDetails = raw.prompt_tokens_details || {};
        value.cachedTokens = promptDetails.cached_tokens !== undefined ? promptDetails.cached_tokens : (raw.cached_tokens !== undefined ? raw.cached_tokens : 0);
        var completionDetails = raw.completion_tokens_details || {};
        value.reasoningTokens = completionDetails.reasoning_tokens !== undefined ? completionDetails.reasoning_tokens : 0;
    } else {
        value.promptTokens = estimateTokens(JSON.stringify(body.messages || []));
        value.completionTokens = estimateTokens(content) + estimateTokens(reasoning);
        value.estimated = true;
    }
    return value;
}

exports.complete = function(body, providerId, display) {
    body.stream = true;
    if (!body.stream_options) body.stream_options = {include_usage: true};
    if (body.tools && !body.tools.length) delete body.tools;
    var content = "";
    var reasoning = "";
    var calls = {};
    var finished = false;
    var finishReason = null;
    var assistant = null;
    var startedAt = Date.now();
    var firstTokenAt = 0;
    var streamedUsage = null;
    function noteToken() { if (!firstTokenAt) firstTokenAt = Date.now(); }
    var response = host.call("model.request", {body: body, providerId: providerId, display: !!display}, function(event, data) {
        if (data === "[DONE]") { finished = true; return true; }
        var chunk = JSON.parse(data);
        if (chunk.error) throw new Error(JSON.stringify(chunk.error));
        if (chunk.usage) streamedUsage = chunk.usage;
        if (!chunk.choices || !chunk.choices.length) return false;
        var choice = chunk.choices[0];
        if (choice.finish_reason) finishReason = choice.finish_reason;
        var delta = choice.delta || choice.message || {};
        if (delta.content) { noteToken(); content += exports.text(delta.content); }
        if (delta.reasoning_content) { noteToken(); reasoning += delta.reasoning_content; }
        (delta.tool_calls || []).forEach(function(part) {
            noteToken();
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
    var rawUsage = null;
    if (!response.stream) {
        var complete = JSON.parse(response.body);
        if (complete.error) throw new Error(JSON.stringify(complete.error));
        if (!complete.choices || !complete.choices.length) throw new Error("模型响应缺少 choices");
        assistant = complete.choices[0].message;
        finishReason = complete.choices[0].finish_reason;
        rawUsage = complete.usage || null;
        if (!assistant) throw new Error("模型响应缺少 assistant message");
        assistant.role = "assistant";
        if (display) host.call("emit", {type: "output", value: {text: exports.text(assistant.content)}});
    } else {
        if (!finished && !finishReason) throw new Error("模型流意外中断，未收到完成标记；未自动重发");
        assistant = {role: "assistant", content: content || null};
        rawUsage = streamedUsage;
        var toolCalls = Object.keys(calls).sort(function(left, right) { return Number(left) - Number(right); }).map(function(key) { return calls[key]; });
        if (toolCalls.length) assistant.tool_calls = toolCalls;
        if (reasoning) assistant.reasoning_content = reasoning;
    }
    emitModelUsage(readModelUsage(rawUsage, body, content, reasoning, startedAt, firstTokenAt, display));
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
