import com.pholser.junit.quickcheck.generator.GenerationStatus
import com.pholser.junit.quickcheck.generator.Generator
import com.pholser.junit.quickcheck.random.SourceOfRandomness
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.CharArrayReader
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests the behavior of a generator that produces two calls to a random() function on the same line, which would
 * have an identical stack trace.
 */
class SameLineRandomTest {
    class IntGenerator : Generator<Int>(Int::class.java) {
        override fun generate(random: SourceOfRandomness, status: GenerationStatus): Int {
            // These must be on the same line
            return makeInt(random.nextInt(0, 256), random.nextInt(0, 256))
        }

        // Takes a as upper 2 bytes and b as lower 2 bytes
        private fun makeInt(a: Int, b: Int): Int = (a shl 16) or (b and 0xFFFF)
    }

    @Test
    fun testSameStackTrace() {
        testServer(IntGenerator(), "IntTestDriver", "testDummy") { server ->
            // Ensure exactly 2 calls to random appeared
            val eiHandler = server.responseHandlers["ei"] ?: error("couldn't find stack trace handler")
            assertEquals("OK", eiHandler.onPatch(CharArrayReader("[]".toCharArray()).buffered()))
            val initialGenResult: List<LocWithData> = Json.decodeFromString(eiHandler.onGet())
            // Should be 8 due to 4 bytes per int
            assertEquals(8, initialGenResult.size)
        }
    }
}