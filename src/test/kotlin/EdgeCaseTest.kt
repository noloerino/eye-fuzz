import com.pholser.junit.quickcheck.generator.GenerationStatus
import com.pholser.junit.quickcheck.generator.Generator
import com.pholser.junit.quickcheck.random.SourceOfRandomness
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.CharArrayReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EdgeCaseTest {
    class OneLineIntGenerator : Generator<Int>(Int::class.java) {
        override fun generate(random: SourceOfRandomness, status: GenerationStatus): Int {
            // These must be on the same line
            return makeInt(random.nextInt(0, 0x10000), random.nextInt(0, 0x10000))
        }

        // Takes a as upper 2 bytes and b as lower 2 bytes
        private fun makeInt(a: Int, b: Int): Int = (a shl 16) or (b and 0xFFFF)
    }

    class TwoLineIntGenerator : Generator<Int>(Int::class.java) {
        override fun generate(random: SourceOfRandomness, status: GenerationStatus): Int {
            // These must be on different lines
            return makeInt(
                    random.nextInt(0, 0x10000),
                    random.nextInt(0, 0x10000)
            )
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
        testServer(TwoLineIntGenerator(), "IntTestDriver", "testDummy", listOf()) { server ->
            // IntGenerator produces 2 ints and combines their lower 2 bytes together
            // Combining 0xCCCCDEAD (generated first) with 0xCCCCBEEF will give us 0xDEADBEEF.
            server.newSavedInput(fileName,
                    // These literals get converted to ints, so this saves some keystrokes
                    intArrayOf(0xAD, 0xDE, 0xCC, 0xCC, 0xEF, 0xBE, 0xCC, 0xCC)
                            .map { it.toByte() }
                            .toByteArray())
            val loadHandler = server.getResponseHandler("load_input")
            val fileList = Json.decodeFromString<List<String>>(loadHandler.onGet())
            assertTrue(fileList.contains(fileName), "didn't find saved input $fileName in reported $fileList")
            assertEquals("OK", loadHandler.postJson(Server.SaveLoadRequest(fileName)))
            val genHandler = server.getResponseHandler("generator")
            assertEquals(0xDEADBEEF.toInt().toString(), genHandler.postString(""))
        }
    }

    /**
     * Tests the behavior of a generator that produces two calls to a random() function on the same line, which would
     * have an identical stack trace.
     */
    @Test
    fun testSameStackTrace() {
        testServer(OneLineIntGenerator(), "IntTestDriver", "testDummy", listOf()) { server ->
            // Ensure exactly 2 calls to random appeared
            val eiHandler = server.getResponseHandler("ei")
            assertEquals("OK", eiHandler.onPatch(CharArrayReader("[]".toCharArray()).buffered()))
            val initialGenResult: List<LocWithData> = Json.decodeFromString(eiHandler.onGet())
            // Should be 8 due to 4 bytes per int
            assertEquals(8, initialGenResult.size)
        }
    }
}