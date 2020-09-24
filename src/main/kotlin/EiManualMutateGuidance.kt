import Server.eventToString
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException
import edu.berkeley.cs.jqf.fuzz.guidance.Result
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.ReturnEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.function.Consumer

/**
 * Analogous to a ExecutionIndexGuidance, but is backed by the above eiMap.
 * Needs to record coverage for EI to work.
 * Run only once.
 */
class EiManualMutateGuidance(private val rng: Random) : Guidance {
    private var appThread: Thread? = null // Ensures only one thread
    private var lastEvent: TraceEvent? = null
    private var eiState = EiState()
    private var hasRun = false

    /**
     * Tracks which EIs were used in the most recent run of the generator.
     *
     * Since EIs are unique (as visiting the same location twice would result in an incremented count),
     * we're able to use a LinkedSet instead of an ordinary list.
     */
    val usedThisRun = linkedSetOf<ExecutionIndex>()
    var eiMap = linkedMapOf<ExecutionIndex, EiData>()

    /**
     * A stream of integers that will be consumed to fill values in the EI map.
     * Should be null when not performing repro runs.
     */
    private var reproValues: Iterator<Int>? = null

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
        eiMap.clear()
        reproValues = file.readBytes().asSequence().map { it.toInt() and 0xFF }.iterator()
        return Closeable {
            reproValues = null
        }
    }

    fun reset() {
        eiState = EiState()
        hasRun = false
        usedThisRun.clear()
    }

    /**
     * the full stack trace of the current EI state
     * should be invoked after getExecutionIndex was called on lastEvent
     */
    private val fullStackTrace: List<StackTraceLine>
        get() = (0 until eiState.depth + 1).map { i ->
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
                usedThisRun.add(executionIndex)
                // Attempt to get a value from the map, or else generate a random value
                return eiMap.computeIfAbsent(executionIndex) {
                    EiData(
                            fullStackTrace,
                            reproValues?.next() ?: rng.nextInt(256)
                    )
                }.choice
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
        return usedThisRun.map { k -> eiMap[k]!!.choice.toByte() }.toByteArray()
    }

    fun writeLastRunToFile(dest: File) {
        dest.writeBytes(getLastRunBytes())
    }

    companion object {
        private const val verbose = false
    }
}