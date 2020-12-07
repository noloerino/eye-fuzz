import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests that sessions can be serialized and deserialized properly.
 */
class SessionSerializationTest {
    private val rng = Random(0)

    /**
     * Tests load/unload behavior for a simple session, checking that the history in the server is exactly
     * what's loaded in.
     * Since stack traces may change and there's some weird stuff that happens with the initial load, we only compare
     * the runResult sequences minus last element.
     */
    @Test
    fun testSimpleSession() {
        val sessionName = "simple_session0.json"
        val sessionJson: String = javaClass.getResourceAsStream(sessionName).bufferedReader().use { it.readText() }
        testServer(JavaScriptCodeGenerator(), "JsTestDriver", "testWithGenerator",
                listOf("com.google.javascript.jscomp.Compiler")) { server ->
            val expHistory: FuzzHistory = Json.decodeFromString(sessionJson)
            server.newSavedSession("__TEST_$sessionName", expHistory)
            val loadSessionHandler = server.getResponseHandler("load_session")
            val sessionList = Json.decodeFromString<List<String>>(loadSessionHandler.onGet())
            assertTrue(sessionList.contains("__TEST_$sessionName"),
                    "didn't find saved session __TEST_$sessionName in reported $sessionList")
            loadSessionHandler.postJson(Server.SaveLoadRequest("__TEST_$sessionName"))
            // This might be sensitive to server-side stack trace filtering, so only examine RunResult
            val gotHistory = server.getResponseHandler("history").onGet()
            // Drop last because the generator is rerun, which produces an extraneous result
            assertEquals(expHistory.runResults, Json.decodeFromString<FuzzHistory>(gotHistory).runResults.dropLast(1))
        }
    }

    /**
     * Simple unit test for encoding/decoding of a history.
     */
    @Test
    fun testEncodeDecode() {
        val state = EiManualMutateGuidance<String>(rng.asJavaRandom()) { v -> v.toString() }.fuzzState
        // Mock 3-6 iterations of fuzzing
        val eis = mutableSetOf<StackTraceInfo>()
        (0..(max(3 + rng.nextInt(3), eis.size))).forEach { _ ->
            // mark new run
            state.resetForNewRun()
            // update 0-3 EI
            val toUpdate = eis.shuffled(rng).take(rng.nextInt(4))
            toUpdate.forEach {
                state.update(locList.indexOf(it), rng.nextInt(255))
            }
            // create 0-2 EI
            val newEis = (0..rng.nextInt(3)).map { newStackTraceInfo() }
            eis.addAll(newEis)
            newEis.forEach { state.add(it) }
        }
        // Make clones of the list in case some funky mutation occurs
        val originalHistory = state.history.copy(locList.toList(), state.history.runResults)
        val decodedHistory: FuzzHistory = Json.decodeFromString(Json.encodeToString(originalHistory))
        assertEquals(originalHistory, decodedHistory)
    }

    private fun newStackTraceInfo(): StackTraceInfo {
        val stackTrace = (0..(1 + rng.nextInt(11))).map {
            StackTraceLine(
                    "TestClass",
                    "TestFile.java",
                    0,
                    "testMethod",
                    0
            )
        }
        // everything is byte for simplicity
        val typeInfo = ByteTypeInfo(ChoiceKind.BYTE, 0, null)
        return StackTraceInfo(stackTrace, typeInfo)
    }
}