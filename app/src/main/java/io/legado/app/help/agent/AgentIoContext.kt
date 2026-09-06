package io.legado.app.help.agent

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class AgentIoContext(val control: AgentControl) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentIoContext>
}
