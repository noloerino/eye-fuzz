import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests that sessions can be serialized and deserialized properly.
 */
class SessionSerializationTest {
    private val rng = Random(0)

    @Test
    fun testEncodeDecode() {
        val state = EiManualMutateGuidance<String>(rng.asJavaRandom()).fuzzState
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
        val decodedHistory: FuzzHistory<String> = Json.decodeFromString(Json.encodeToString(originalHistory))
        assertEquals(originalHistory, decodedHistory)
    }

    private fun newStackTraceInfo(): StackTraceInfo {
        val stackTrace = (0..(1 + rng.nextInt(11))).map {
            StackTraceLine(
                    "TestClass",
                    "TestFile.java",
                    0,
                    "testMethod"
            )
        }
        // everything is byte for simplicity
        val typeInfo = ByteTypeInfo(ChoiceKind.BYTE, 0, null)
        return StackTraceInfo(stackTrace, typeInfo)
    }
}