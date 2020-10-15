import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex;
import edu.berkeley.cs.jqf.fuzz.util.Counter;
import edu.berkeley.cs.jqf.fuzz.util.NonZeroCachingCounter;
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.ReturnEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEventVisitor;

import java.util.ArrayList;
import java.util.Arrays;

// Copied from https://github.com/rohanpadhye/JQF/blob/master/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/ei/ExecutionIndexingState.java
// Used to allow some transparency in behavior and to allow print statement insertion :P
public class EiState implements TraceEventVisitor {
    private final int COUNTER_SIZE = 6151;
    private final int MAX_SUPPORTED_DEPTH = 1024; // Nothing deeper than this

    public int depth = 0;
    private final ArrayList<Counter> stackOfCounters = new ArrayList<>();
    public final int[] rollingIndex = new int[2*MAX_SUPPORTED_DEPTH];

    public EiState() {
        // Create a counter for depth = 0
        stackOfCounters.add(new NonZeroCachingCounter(COUNTER_SIZE));
    }

    public void pushCall(CallEvent e) {
        // Increment counter for call-site (note: this is subject to hash collisions)
        int count = stackOfCounters.get(depth).increment(e.getIid());

        // Add to rolling execution index
        rollingIndex[2*depth] = e.getIid();
        rollingIndex[2*depth + 1] = count;

        // Increment depth
        depth++;

        // Ensure that we do not go very deep
        if (depth >= MAX_SUPPORTED_DEPTH) {
            throw new StackOverflowError("Very deep stack; cannot compute execution index");
        }

        // Push a new counter if it does not exist
        if (depth >= stackOfCounters.size()) {
            stackOfCounters.add(new NonZeroCachingCounter(COUNTER_SIZE));
        }

    }

    public void popReturn(ReturnEvent e) {
        // Clear the top-of-stack
        try {
            stackOfCounters.get(depth).clear();
        } catch (ArrayIndexOutOfBoundsException ex) {
            String evString = e.getContainingClass() + "#" + e.getContainingMethodName();
            System.out.println("OOB FOR RETURN EVENT " + evString + "; DEPTH " + depth);
            throw ex;
        }
        // Decrement depth
        depth--;

        assert (depth >= 0);
    }

    public ExecutionIndex getExecutionIndex(TraceEvent e) {
        // Increment counter for event (note: this is subject to hash collisions)
        int count = stackOfCounters.get(depth).increment(e.getIid());

        // Add to rolling execution index
        rollingIndex[2*depth] = e.getIid();
        rollingIndex[2*depth + 1] = count;

        // Snapshot the rolling index
        int size = 2*(depth+1); // 2 integers for each depth value
        int[] ei = Arrays.copyOf(rollingIndex, size);

        // Create an execution index
        return new ExecutionIndex(ei);
    }

    @Override
    public void visitCallEvent(CallEvent c) {
        String evString = c.getContainingClass() + "#" + c.getContainingMethodName() + " --> "
                + c.getInvokedMethodName();
//        System.out.println("EI CALL @ DEPTH " + depth + ": " + evString);
        this.pushCall(c);
    }

    @Override
    public void visitReturnEvent(ReturnEvent r) {
        String evString = r.getContainingClass() + "#" + r.getContainingMethodName();
//        System.out.println("EI RET @ DEPTH " + depth + ": " + evString);
        this.popReturn(r);
    }
}
