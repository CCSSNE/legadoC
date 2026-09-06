# Agent 宿主 API 1

ZIP 根包含 manifest.json 与入口 JS。模块使用 CommonJS exports；require 仅接受包根相对的完整 `.js` 路径，依赖包使用 `@插件ID/路径.js`，不猜扩展名，不支持 Node/npm。

`exports.run(input, host)` 返回字符串或 `{output}`。`host.call(operation, JSON对象, 可选SSE回调)` 同步等待可取消的宿主能力。SSE 回调接收 event/data，返回 true 结束读取。模型请求不自动重试。

操作：config、model.reference、model.request、http、modules.list、tools.discover、tools.call、context.snapshot、context.refresh、messages.list、messages.append、events.list、emit、log、pause、checkpoint、input、prompts.get/list、skills.get/list/resources、resources.read、storage.get/list/put/delete/transaction、vectors.put/search。

工具以 moduleId/toolId 寻址，tools.discover 返回固定模型名和真实 schema。tools.call 参数为 `{moduleId,toolId,arguments}`。调用时重新检查最新开关。message 按通用协议保存完整 tool_calls/tool_call_id。

tools.discover 可传 `{moduleId}` 取得指定模块定义；同一任务只发现一次目录，并固定代码与连接配置。skills.resources 接收 `{key}` 列资源，`{key,path,encoding:"utf8"或"base64"}` 读取资源。插件工具对外执行时遵守对外模块开关，不借用内部开关授权未对外开放的能力。

model.request 参数为 `{providerId,body}`，body 是完整通用 chat/completions 请求。http 参数为 `{url,method,headers,body,contentType}`。供应商认证由宿主读取，不随代码导出。

storage 使用插件隔离的 namespace/key；put 可提供 revision 做乐观锁；transaction 接受 operations 数组。vectors 操作包含 namespace/key/space/vector/contentRevision，向量空间和维度不得混用。

调试可使用 debugger;、pause 或 checkpoint。管理页可暂停/继续/输入/停止并查看调用栈和变量。保存产生不可变修订，运行固定修订与依赖快照，停止后重新运行使用新修订。历史修订只能手动选择，不自动回退。

可在 manifest.tools 声明 `{id,description,inputSchema,entry,export}`，entry 必须是包内 .js 文件，导出函数接收 JSON 参数并返回结构化工具结果。提示词按唯一 key 引用；Skill 是知识指导，不会变成工具。

默认配置与源码都可查看。上下文预算是可编辑的 JS 请求策略，不截断原始记录；记忆召回、自动写入、排序和压缩均在 lib 中维护。
