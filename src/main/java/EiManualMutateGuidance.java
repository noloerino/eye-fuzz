import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop;
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.ReturnEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Analogous to a ExecutionIndexGuidance, but is backed by the above eiMap.
 * Needs to record coverage for EI to work.
 * Run only once.
 */
public class EiManualMutateGuidance implements Guidance {
    private Thread appThread = null; // Ensures only one thread
    private TraceEvent lastEvent = null;
    private EiState eiState = new EiState();
    private boolean hasRun = false;

    public LinkedHashMap<ExecutionIndex, EiData> eiMap = new LinkedHashMap<>();
    private final Random rng;

    public EiManualMutateGuidance(Random rng) {
        this.rng = rng;
    }

    public void reset() {
        eiState = new EiState();
        hasRun = false;
    }

    /**
     * @return the full stack trace of the current EI state
     * should be invoked after getExecutionIndex was called on lastEvent
     */
    private String getFullStackTrace() {
        String[] lines = new String[eiState.depth + 1];
        // Manual copy because we don't care about counts
        for (int i = 0; i < eiState.depth + 1; i++) {
            int iid = eiState.rollingIndex[2 * i];
            int count = eiState.rollingIndex[2 * i + 1];
            lines[i] = "count " + count + " " + Server.eventStrings.get(iid);
        }
        return Arrays.stream(lines).collect(Collectors.joining(" || "));
    }

    @Override
    public InputStream getInput() throws IllegalStateException, GuidanceException {
        return new InputStream() {
            @Override
            public int read() {
                if (lastEvent == null) {
                    throw new GuidanceException("Could not compute execution index; no instrumentation?");
                }
                // Get the execution index of the last event
                ExecutionIndex executionIndex = eiState.getExecutionIndex(lastEvent);
                log("\tREAD " + Server.eventToString(lastEvent));
                // Attempt to get a value from the map, or else generate a random value
                return eiMap.computeIfAbsent(
                        executionIndex,
                        ei -> new EiData(getFullStackTrace(), rng.nextInt(256))
                ).choice;
            }
        };
    }

    @Override
    public boolean hasInput() {
        return !hasRun;
    }

    @Override
    public void handleResult(Result result, Throwable throwable) throws GuidanceException {
//        System.out.println("\tHANDLE RESULT");
        hasRun = true;
    }

    @Override
    public Consumer<TraceEvent> generateCallBack(Thread thread) {
        if (appThread != null) {
            throw new IllegalStateException("Guidance must run on main thread");
        }
        appThread = thread;
        String entryPoint = SingleSnoop.entryPoints.get(thread);
        if (entryPoint == null) {
            throw new IllegalStateException("Guidance must be able to determine entry point");
        }
        return this::handleEvent;
    }

    private static final boolean verbose = false;
    private void log(String msg) {
        if (verbose) {
            System.out.println(msg);
        }
    }

    private boolean isTracking = false;
    private void handleEvent(TraceEvent e) {
        // Needed to cache stack traces
        String contents = Server.eventToString(e);
//            System.out.println("BEGIN VISIT");
        if (e instanceof CallEvent) {
            if (e.getContainingMethodName().equals("runGenerator")) {
//            if (((CallEvent) e).getInvokedMethodName().equals("Server#dummy()V")) {
                isTracking = true;
            }
            String trackedString = isTracking ? "*tracked" : "untracked";
            log("CALL " + trackedString + ": " + contents);
        } else if (e instanceof ReturnEvent) {
            String evString = e.getContainingClass() + "#" + e.getContainingMethodName();
//                if (evString.equals("com/pholser/junit/quickcheck/internal/GeometricDistribution#<init>")) {
//                if (evString.equals("Server#runGenerator")) {
            if (evString.contains("dummy")) {
                isTracking = false;
            }
            String trackedString = isTracking ? "*tracked" : "untracked";
            log("RET " + trackedString + ": " + evString);
        } else {
            String trackedString = isTracking ? "*tracked" : "untracked";
            log("OTHER " + trackedString + ": " + contents);
        }
        if (isTracking) {
            e.applyVisitor(eiState);
        }
        lastEvent = e;
//            System.out.println("END VISIT");
    }
}

