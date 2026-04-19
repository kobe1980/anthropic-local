import kotlin.test.Test
import kotlin.test.assertEquals

class JsonParsingTest {

    @Test
    fun extractTopLevelString_reads_string_field() {
        val json = """{"model":"local-gemma","max_tokens":200}"""
        assertEquals("local-gemma", extractTopLevelString(json, "model"))
    }

    @Test
    fun extractTopLevelInt_reads_int_field() {
        val json = """{"model":"local-gemma","max_tokens":200}"""
        assertEquals(200, extractTopLevelInt(json, "max_tokens"))
    }

    @Test
    fun extractMessages_reads_simple_messages_array() {
        val json = """
            {
              "messages": [
                {"role":"user","content":"bonjour"}
              ]
            }
        """.trimIndent()

        val messages = extractMessages(json)

        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("bonjour", messages[0].content)
    }

    @Test
    fun extractMessages_reads_content_array_text_blocks() {
        val json = """
            {
              "messages": [
                {
                  "role":"user",
                  "content":[
                    {"type":"text","text":"ligne 1"},
                    {"type":"text","text":"ligne 2"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val messages = extractMessages(json)

        assertEquals(1, messages.size)
        assertEquals("ligne 1\nligne 2", messages[0].content)
    }
}
