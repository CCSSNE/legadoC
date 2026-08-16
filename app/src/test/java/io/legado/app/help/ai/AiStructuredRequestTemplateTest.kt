package io.legado.app.help.ai

import org.junit.Test

class AiStructuredRequestTemplateTest {

    @Test
    fun validate_allowsMissingKnownTokensAndUnknownTokens() {
        AiStructuredRequestTemplate.validate("""{"payload":"{{customValue}}"}""")
    }

    @Test
    fun validate_allowsNoKnownTokens() {
        AiStructuredRequestTemplate.validate("""{"payload":"fixed"}""")
    }
}
