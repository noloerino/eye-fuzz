import com.pholser.junit.quickcheck.generator.GenerationStatus
import com.pholser.junit.quickcheck.generator.Generator
import com.pholser.junit.quickcheck.random.SourceOfRandomness
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.CharArrayReader
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeCaseTests {
    class IntGenerator : Generator<Int>(Int::class.java) {
        override fun generate(random: SourceOfRandomness, status: GenerationStatus): Int {
            // These must be on the same line
            return makeInt(random.nextInt(0, 256), random.nextInt(0, 256))
        }

        // Takes a as upper 2 bytes and b as lower 2 bytes
        private fun makeInt(a: Int, b: Int): Int = (a shl 16) or (b and 0xFFFF)
    }

    /**
     * Verifies the little-endian nature of the generator. That is, if integer 0xDEADBEEF is generated, it would be
     * stored in the file as EF | BE | AD | DE starting from offset 0.
     */
    @Test
    fun testLittleEndianLoad() {
        val fileName = "__TEST_LE"
        testServer(IntGenerator(), "IntTestDriver", "testDummy") { server ->
            // IntGenerator produces 2 ints and combines their lower 2 bytes together
            // Combining 0xCCCCDEAD (generated first) with 0xCCCCBEEF will give us 0xDEADBEEF.
            server.newSavedInput(fileName,
                    // These literals get converted to ints, so this saves some keystrokes
                    intArrayOf(0xAD, 0xDE, 0xCC, 0xCC, 0xEF, 0xBE, 0xCC, 0xCC)
                            .map { it.toByte() }
                            .toByteArray())
            val loadHandler = server.responseHandlers["load_input"] ?: error("couldn't find saved input load handler")
            val fileList = Json.decodeFromString<List<String>>(loadHandler.onGet())
            assertTrue(fileList.contains(fileName), "didn't find saved input $fileName in reported $fileList")
            assertEquals("OK", loadHandler.postJson(Server.SaveLoadRequest(fileName)))
        }
    }

    /**
     * Tests the behavior of a generator that produces two calls to a random() function on the same line, which would
     * have an identical stack trace.
     */
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