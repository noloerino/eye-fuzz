import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException
import edu.berkeley.cs.jqf.fuzz.guidance.Result
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop
import edu.berkeley.cs.jqf.instrument.tracing.events.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.function.Consumer
import kotlin.collections.LinkedHashMap

enum class GuidanceMode {
    GENERATE_INPUT,
    REPRO_FILE,
    COLLECT_COV,
}

/**
 * A more transparent version of ExecutionIndexingGuidance that also doubles as a repro guidance and also a collector
 * of test coverage, because there's some funky thread stuff going on.
 */
class EiManualMutateGuidance(rng: Random, private val appThread: Thread) : Guidance {
    private var lastEvent: TraceEvent? = null
    /** The EI stack trace for generator runs */
    private var genEiState = EiState()
    /** The EI stack trace for test coverage runs */
    private var collectEiState = EiState()
    val lastRunTestCov = mutableSetOf<TraceEvent>()

    private var mode = GuidanceMode.GENERATE_INPUT

    private var hasRun = false

    val fuzzState = FuzzState(this, rng)
    val history: FuzzHistory by fuzzState::history

    /**
     * During a repro run, the original EI map is saved here. Any values that did not appear in the repro EI run
     * are thereby preserved for diffing purposes.
     *
     * Outside a repro run, this should be null. Moving the original map to here instead of making this the repro map
     * and performing a null check hopefully saves cycles, although maybe the JIT would've saved them anyway.
     */
    private var reproBackupEiMap: LinkedHashMap<ExecutionIndex, EiData>? = null

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
        reproBackupEiMap = fuzzState.eiMap
        fuzzState.eiMap = linkedMapOf()
        reproValues = file.readBytes().asSequence().map { it.toInt() and 0xFF }.iterator()
        return Closeable {
            // Careful with ordering here - we want the new EI map to override the values in the backed up map
            reproBackupEiMap!!.putAll(fuzzState.eiMap)
            fuzzState.eiMap = reproBackupEiMap!!
            reproBackupEiMap = null
            reproValues = null
            mode = GuidanceMode.GENERATE_INPUT
        }
    }

    /**
     * Sets the guidance to do nothing but collect test coverage; no inputs should ever be generated in this mode.
     */
    fun collectTestCov(): Closeable {
        collectEiState = EiState()
        lastRunTestCov.clear()
        mode = GuidanceMode.COLLECT_COV
        return Closeable {
            mode = GuidanceMode.GENERATE_INPUT
        }
    }

    fun reset() {
        genEiState = EiState()
        hasRun = false
        fuzzState.resetForNewRun()
    }

    /**
     * the full stack trace of the current EI state
     * should be invoked after getExecutionIndex was called on lastEvent
     */
    fun getFullStackTrace(): List<StackTraceLine> = (0 until genEiState.depth + 1).map { i ->
        val iid = genEiState.rollingIndex[2 * i]
        val count = genEiState.rollingIndex[2 * i + 1]
        val callLocation = callLocations[iid]!!
        StackTraceLine(callLocation, count)
    }

    /**
     * When this object is not in repro mode (invoked through `reproWithFile`), this will read existing bytes from
     * the EI map and generate new bytes as necessary. In repro mode, this will read the next byte in the passed
     * in iterator.
     */
    override fun getInput(): InputStream {
        return object : InputStream() {
            override fun read(): Int {
                if (lastEvent == null) {
                    throw GuidanceException("Could not compute execution index; no instrumentation?")
                }
                check(!isInCollectMode) { "Illegal attempt to request bytes while in coverage collection mode" }
                // Get the execution index of the last event
                val executionIndex = genEiState.getExecutionIndex(lastEvent!!)
                log("\tREAD " + eventToString(lastEvent!!))
                return fuzzState.add(executionIndex)
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
        require(appThread == thread) { "Guidance must stay on the same thread" }
        SingleSnoop.entryPoints[thread] ?: throw IllegalStateException("Guidance must be able to determine entry point")
        return Consumer { e: TraceEvent -> handleEvent(e) }
    }

    private fun log(msg: String) {
        if (verbose) {
            println(msg)
        }
    }

    private var isTracking = false
    private fun handleEvent(e: TraceEvent) {
        // Needed to cache stack traces
        val contents = eventToString(e)
        when (e) {
            is CallEvent -> {
                if (e.containingMethodName == Server.GUIDANCE_STUB_METHOD) {
                    isTracking = true
                }
                val trackedString = if (isTracking) "*tracked" else "untracked"
                log("CALL $trackedString: $contents")
            }
            is ReturnEvent -> {
                if (contents.contains(Server.GUIDANCE_STUB_FULL_NAME)) {
                    isTracking = false
                }
                val trackedString = if (isTracking) "*tracked" else "untracked"
                log("RET $trackedString: $contents")
            }
            else -> {
                val trackedString = if (isTracking) "*tracked" else "untracked"
                log("OTHER $trackedString: $contents")
            }
        }
        if (isTracking) {
            e.applyVisitor(if (isInCollectMode) { collectEiState } else { genEiState })
            if (isInCollectMode) {
                lastRunTestCov.add(e)
            }
        }
        lastEvent = e
    }

    /**
     * Produces the sequence of bytes produced by the most recent input.
     *
     * This emulates the behavior of Zest's ExecutionIndexingGuidance.MappedInput, which
     * simply produces all the bytes in the order that they were requested.
     */
    private fun getLastRunBytes(): ByteArray {
        return fuzzState.usedThisRun.map { k -> fuzzState.eiMap[k]!!.choice.toByte() }.toByteArray()
    }

    fun writeLastRun(dest: File) {
        dest.writeBytes(getLastRunBytes())
    }

    fun writeSessionHistory(dest: File) {
        val data = fuzzState.history
        dest.writeText(Json { prettyPrint = true }.encodeToString(data))
    }

    fun loadSessionHistory(src: File) {
        val newHistory: FuzzHistory = Json.decodeFromString(src.readText())
        fuzzState.reloadFromHistory(newHistory)
    }

    companion object {
        private const val verbose = false

        @JvmField
        val eventStrings: MutableMap<Int, String> = mutableMapOf()

        @JvmField
        val callLocations: MutableMap<Int, CallLocation> = mutableMapOf()

        @JvmStatic
        fun eventToString(e: TraceEvent): String {
            return eventStrings.computeIfAbsent(e.iid) {
                when (e) {
                    is BranchEvent -> {
                        String.format("(branch) %s#%s()@%d [%d]", e.containingClass, e.containingMethodName,
                                e.lineNumber, e.arm)
                    }
                    is CallEvent -> {
                        callLocations.computeIfAbsent(e.iid) {
                            CallLocation(e.iid, e.containingClass, e.containingMethodName, e.lineNumber, e.invokedMethodName)
                        }
                        String.format("(call) %s#%s()@%d --> %s", e.containingClass, e.containingMethodName,
                                e.lineNumber, e.invokedMethodName)
                    }
                    is ReturnEvent -> {
                        "(return) ${e.containingClass}#${e.containingMethodName}"
                    }
                    is AllocEvent -> {
                        "(alloc) size ${e.size} ${e.containingClass}#${e.containingMethodName}()@${e.lineNumber}"
                    }
                    is ReadEvent -> {
                        "(read) ${e.field} in ${e.containingClass}#${e.containingMethodName}()@${e.lineNumber}"
                    }
                    else -> {
                        String.format("(other) %s#%s()@%d", e.containingClass, e.containingMethodName, e.lineNumber)
                    }
                }
            }
        }

    }
}