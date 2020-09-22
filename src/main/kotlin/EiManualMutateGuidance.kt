import Server.eventToString
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException
import edu.berkeley.cs.jqf.fuzz.guidance.Result
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.ReturnEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent
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
     * Tracks which EIs were used in the most recent run of the generator
     */
    val usedThisRun = mutableSetOf<ExecutionIndex>()
    var eiMap = LinkedHashMap<ExecutionIndex, EiData>()

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
            StackTraceLine(Server.callLocations[iid]!!, count)
        }

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
                return eiMap.computeIfAbsent(executionIndex) { EiData(fullStackTrace, rng.nextInt(256)) }.choice
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
        check(appThread == null) { "Guidance must run on main thread" }
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
                if (e.getContainingMethodName() == "runGenerator") {
//            if (((CallEvent) e).getInvokedMethodName().equals("Server#dummy()V")) {
                    isTracking = true
                }
                val trackedString = if (isTracking) "*tracked" else "untracked"
                log("CALL $trackedString: $contents")
            }
            is ReturnEvent -> {
                val evString = e.getContainingClass() + "#" + e.getContainingMethodName()
//                if (evString.equals("com/pholser/junit/quickcheck/internal/GeometricDistribution#<init>")) {
//                if (evString.equals("Server#runGenerator")) {
                if (evString.contains("dummy")) {
                    isTracking = false
                }
                val trackedString = if (isTracking) "*tracked" else "untracked"
                log("RET $trackedString: $evString")
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

    companion object {
        private const val verbose = false
    }
}