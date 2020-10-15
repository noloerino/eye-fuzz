import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent
import janala.logger.inst.MemberRef
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.random.Random
import kotlin.random.asJavaRandom
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionSerializationTest {
    private val rng = Random(0)

    @Test
    fun testEncodeDecode() {
        val state = EiManualMutateGuidance(rng.asJavaRandom(), Thread.currentThread()).fuzzState
        // Mock 3-6 iterations of fuzzing
        val eis = mutableSetOf<ExecutionIndex>()
        (0..(max(3 + rng.nextInt(3), eis.size))).forEach { _ ->
            // mark new run
            state.resetForNewRun()
            // update 0-3 EI
            val toUpdate = eis.shuffled(rng).take(rng.nextInt(4))
            toUpdate.forEach {
                state.update(it, rng.nextInt(255))
            }
            // create 0-2 EI
            val newEis = (0..rng.nextInt(3)).map { newExecutionIndex() }
            eis.addAll(newEis)
            newEis.forEach { state.add(it) }
        }
        // Make clones of the list in case some funky mutation occurs
        val originalHistory = state.history.copy(state.history.runResults)
        val decodedHistory: FuzzHistory = Json.decodeFromString(Json.encodeToString(originalHistory))
        assertEquals(originalHistory, decodedHistory)
    }

    private fun newExecutionIndex(): ExecutionIndex {
        val arr = (0..(1 + rng.nextInt(11))).flatMap { listOf(it, 1) }.toIntArray()
        // Create dummy CallEvents so stack trace can be serialized
        arr.withIndex().forEach {(i, v) ->
            if (i and 1 == 0) {
                EiManualMutateGuidance.eventToString(CallEvent(
                        v,
                        object : MemberRef {
                            override fun getOwner(): String = "DummyOwner"
                            override fun getName(): String = "dummyName"
                            override fun getDesc(): String = "dummyDesc"
                        },
                        0,
                        object : MemberRef {
                            override fun getOwner(): String = "DummyDest"
                            override fun getName(): String = "dummyDestName"
                            override fun getDesc(): String = "dummyDestDesc"
                        }
                ))
            }
        }
        return ExecutionIndex(arr)
    }
}