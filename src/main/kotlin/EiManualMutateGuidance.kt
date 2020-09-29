import Server.eventToString
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException
import edu.berkeley.cs.jqf.fuzz.guidance.Result
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.ReturnEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.function.Consumer
import kotlin.collections.LinkedHashMap

/**
 * Analogous to a ExecutionIndexGuidance, but is backed by the above eiMap.
 * Needs to record coverage for EI to work.
 * Run only once.
 */
class EiManualMutateGuidance(rng: Random) : Guidance {
    private var appThread: Thread? = null // Ensures only one thread
    private var lastEvent: TraceEvent? = null
    private var eiState = EiState()
    private var hasRun = false

    val fuzzState = FuzzState(this, rng)
    val history by ::fuzzState

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

    val isInReproMode: Boolean get() = reproValues != null

    /**
     * Sets the guidance to read input bytes from the provided file. This automatically calls `reset` to start
     * a new run, and clears the existing EI map.
     *
     * Until the returned closeable is closed, this guidance can only be used for repro; otherwise, the guidance
     * will read from saved values in the map.
     */
    fun reproWithFile(file: File): Closeable {
        this.reset()
        reproBackupEiMap = fuzzState.eiMap
        fuzzState.eiMap = linkedMapOf()
        reproValues = file.readBytes().asSequence().map { it.toInt() and 0xFF }.iterator()
        return Closeable {
            // Careful with ordering here - we want the new EI map to override the values in the backed up map
            reproBackupEiMap!!.putAll(fuzzState.eiMap)
            fuzzState.eiMap = reproBackupEiMap!!
            reproBackupEiMap = null
            reproValues = null
        }
    }

    fun reset() {
        eiState = EiState()
        hasRun = false
        fuzzState.resetForNewRun()
    }

    /**
     * the full stack trace of the current EI state
     * should be invoked after getExecutionIndex was called on lastEvent
     */
    fun getFullStackTrace(): List<StackTraceLine> = (0 until eiState.depth + 1).map { i ->
        val iid = eiState.rollingIndex[2 * i]
        val count = eiState.rollingIndex[2 * i + 1]
        val callLocation = Server.callLocations[iid]!!
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
                // Get the execution index of the last event
                val executionIndex = eiState.getExecutionIndex(lastEvent!!)
                log("\tREAD " + eventToString(lastEvent!!))
                return fuzzState.add(executionIndex)
            }
        }
    }

    override fun hasInput(): Boolean {
        return !hasRun
    }

    override fun handleResult(result: Result, throwable: Throwable) {
//        System.out.println("\tHANDLE RESULT");
        hasRun = true
    }

    override fun generateCallBack(thread: Thread): Consumer<TraceEvent> {
        check(appThread == null || appThread == thread) { "Guidance must stay on the same thread" }
        appThread = thread
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
        //            System.out.println("BEGIN VISIT");
        when (e) {
            is CallEvent -> {
                if (e.containingMethodName == "runGenerator") {
//            if (((CallEvent) e).getInvokedMethodName().equals("Server#dummy()V")) {
                    isTracking = true
                }
                val trackedString = if (isTracking) "*tracked" else "untracked"
                log("CALL $trackedString: $contents")
            }
            is ReturnEvent -> {
//                if (evString.equals("com/pholser/junit/quickcheck/internal/GeometricDistribution#<init>")) {
//                if (evString.equals("Server#runGenerator")) {
                if (contents.contains("Server#runGenerator")) {
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
            e.applyVisitor(eiState)
        }
        lastEvent = e
        //            System.out.println("END VISIT");
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

    fun writeLastRunToFile(dest: File) {
        dest.writeBytes(getLastRunBytes())
    }

    fun writeSessionHistoryToFile(dest: File) {
        val data = fuzzState.history
        dest.writeText(Json { prettyPrint = true }.encodeToString(data))
    }

    companion object {
        private const val verbose = false
    }
}