import edu.berkeley.cs.jqf.fuzz.guidance.Guidance
import edu.berkeley.cs.jqf.fuzz.guidance.Result
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.function.Consumer

enum class GuidanceMode {
    GENERATE_INPUT,
    REPRO_FILE,
    COLLECT_COV,
}

/**
 * A modified version of ExecutionIndexingGuidance that doesn't use ExecutionIndex. Go figure.
 *
 * ExecutionIndex is eschewed in favor of java.lang.Thread.getCurrentStackTrace, which is more reliable for our purposes
 * because we don't specifically need EI, and need to leave ASM out of -Xbootclasspath in order to allow Jacoco to
 * instrument and collect coverage on the fly.
 *
 * This class also doubles as a repro guidance and also a collector of test coverage, because there's some funky
 * thread stuff going on.
 */
class EiManualMutateGuidance<T>(rng: Random) : Guidance {
    var annotatingRandomSource: AnnotatingRandomSource? = null

    private var mode = GuidanceMode.GENERATE_INPUT

    private var hasRun = false

    val fuzzState = FuzzState(this, rng)
    val history: FuzzHistory<T> by fuzzState::history

    /**
     * During a repro run, the original choice map is saved here. Any values that did not appear in the choice run
     * are thereby preserved for diffing purposes.
     *
     * Outside a repro run, this should be null. Moving the original map to here instead of making this the repro map
     * and performing a null check hopefully saves cycles, although maybe the JIT would've saved them anyway.
     */
    private var reproBackupChoiceMap: ChoiceMap? = null

    /**
     * A stream of integers that will be consumed to fill values in the EI map.
     * Should be null when not performing repro runs.
     */
    var reproValues: Iterator<Int>? = null

    val isInReproMode: Boolean get() = mode == GuidanceMode.REPRO_FILE
    val isInCollectMode: Boolean get() = mode == GuidanceMode.COLLECT_COV

    /**
     * Sets the guidance to read input bytes from the provided file. This automatically calls `reset` to start
     * a new run, and clears the existing EI map.
     *
     * Until the returned closeable is closed, this guidance can only be used for repro; otherwise, the guidance
     * will read from saved values in the map.
     */
    fun reproWithFile(file: File): Closeable {
        this.reset()
        mode = GuidanceMode.REPRO_FILE
        reproBackupChoiceMap = fuzzState.choiceMap
        fuzzState.choiceMap = linkedMapOf()
        reproValues = file.readBytes().asSequence().map { it.toInt() and 0xFF }.iterator()
        return Closeable {
            // Careful with ordering here - we want the new choice map to override the values in the backed up map
            reproBackupChoiceMap!!.putAll(fuzzState.choiceMap)
            fuzzState.choiceMap = reproBackupChoiceMap!!
            reproBackupChoiceMap = null
            reproValues = null
            mode = GuidanceMode.GENERATE_INPUT
        }
    }

    /**
     * Sets the guidance to do nothing but collect test coverage; no inputs should ever be generated in this mode.
     */
    fun collectTestCov(): Closeable {
        mode = GuidanceMode.COLLECT_COV
        return Closeable {
            mode = GuidanceMode.GENERATE_INPUT
        }
    }

    fun reset() {
        hasRun = false
        fuzzState.resetForNewRun()
    }

    /**
     * When this object is not in repro mode (invoked through `reproWithFile`), this will read existing bytes from
     * the choice map and generate new bytes as necessary. In repro mode, this will read the next byte in the passed
     * in iterator.
     */
    override fun getInput(): InputStream {
        return object : InputStream() {
            override fun read(): Int {
                check(!isInCollectMode) { "Illegal attempt to request bytes while in coverage collection mode" }
                val info = annotatingRandomSource!!.consumeNextStackTraceInfo()
                log("\tREAD $info")
                return fuzzState.add(info)
            }
        }
    }

    override fun hasInput(): Boolean {
        return !hasRun
    }

    override fun handleResult(result: Result, throwable: Throwable) {
        hasRun = true
    }

    override fun generateCallBack(thread: Thread): Consumer<TraceEvent> {
        // Do nothing lol
        return Consumer {}
    }

    private fun log(msg: String) {
        if (verbose) {
            println(msg)
        }
    }
    /**
     * Produces the sequence of bytes produced by the most recent input.
     *
     * This emulates the behavior of Zest's ExecutionIndexingGuidance.MappedInput, which
     * simply produces all the bytes in the order that they were requested.
     */
    private fun getLastRunBytes(): ByteArray {
        return fuzzState.usedThisRun.map { k -> fuzzState.choiceMap[k]!!.toByte() }.toByteArray()
    }

    fun writeLastRun(dest: File) {
        dest.writeBytes(getLastRunBytes())
    }

    fun writeSessionHistory(dest: File) {
        val data = fuzzState.history
        dest.writeText(Json { prettyPrint = true }.encodeToString(data))
    }

    fun loadSessionHistory(src: File) {
        val newHistory: FuzzHistory<T> = Json.decodeFromString(src.readText())
        fuzzState.reloadFromHistory(newHistory)
    }

    companion object {
        private const val verbose = false
    }
}