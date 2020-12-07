import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness
import kotlinx.serialization.Serializable
import java.io.Closeable

enum class ChoiceKind {
    BOOLEAN,
    BYTE,
    BYTE_ARRAY, // TODO figure out byte array size if necessary
    CHAR,
    CHOOSE,
    DOUBLE,
    FLOAT,
    INT,
    LONG,
    SHORT
}

/**
 * Represents upper and lower bounds on an integer data type up to sizeof(int).
 * This allows support for methods like nextByte(min, max) that make a call to fastChooseByteInRange, which
 * does some modular math for faster generation.
 *
 * min is inclusive, max is exclusive
 */
@Serializable
data class Bounds(val min: Int, val max: Int)

/**
 * Represents type information for a byte mapped at a program location. The system is little endian, meaning that a
 * byteOffset of 0 corresponds to the lowest 8 bits of an int, and similar for other data types.
 */
@Serializable
data class ByteTypeInfo(val kind: ChoiceKind, val byteOffset: Int, val intBounds: Bounds? = null)

data class RandomChoiceState(
        val stackTrace: StackTrace,
        var currType: ChoiceKind,
        var currOfs: Int = 0,
        val currBounds: Bounds? = null
)

// Stack trace is reversed (most recent, which is always Thread::getStackTrace itself, is at index 0)
// Remove everything in this class and after guidanceStub, which is an implementation detail
private fun getCurrentStackTrace(): StackTrace {
    val full = Thread.currentThread().stackTrace.map { it.toLine() }
    val randIdx = full.indexOfLast { it.className == AnnotatingRandomSource::class.java.name }
    val stubIdx = full.indexOfFirst {
        it.className == Server::class.java.name && it.methodName == Server.GUIDANCE_STUB_METHOD_NAME
    }
    return full.subList(randIdx, stubIdx)
}

/**
 * Intercepts calls to FastSourceOfRandomness and annotates it with relevant type information and stack traces.
 * A new instance of this class is created on each generator run.
 *
 * Unsupported methods: nextBigInteger, nextInstant, nextDuration
 */
class AnnotatingRandomSource(delegate: StreamBackedRandom) : FastSourceOfRandomness(delegate) {
    // Keep track of which function actually was the top level
    var depth = 0
    private var choiceState: RandomChoiceState? = null

    /*
     * a(randBool())
     *
     * a(multiple) { if (multiple) for () { random() } random(); }
     */

    // TODO do some kind of caching for stack traces to ensure that when a new one is encountered, the relevant
    // count is incremented
    //
    // Consider the case where we have two identical stack traces (A, B, C), where A, B, and C are all functions that
    // have only a single line.
    // There are at least two distinct ways this could have occurred, both of which may plausibly be possible in the
    // same program due to boolean short-circuiting or even just pathological use of semicolons:
    //
    //   fun A() {
    //       check ? (B() && B()) : B();
    //   }
    //   fun B() {
    //       !check ? (C() && C()) : C();
    //       return true;
    //   }
    //   fun C() {
    //       random();
    //       return true;
    //   }
    //
    // - Function B is called twice in one occurrence at A; B performs an invocation to C each time (check is true)
    //   (A1, B1, C1), (A1, B2, C1)
    // - Function C is called twice at B; B is called once at A; B calls C twice (check is false)
    //   (A1, B1, C1), (A1, B1, C2)
    //
    // Unlike Zest, we cannot meaningfully distinguish between these two cases because random() has no additional
    // knowledge about where B()/C() was invoked besides line number. The only thing we can count is how many times
    // C() was called after being called by A and B at that particular location.
    // In a single run, this is actually sufficient for our purposes: all we need is a way to distinguish between each
    // time we see the (A, B, C) stack trace triple; since each occurrence of the stack trace corresponds to a different
    // choice, we can assign them different IDs and call it a day. However, this won't work if branching logic changes;
    // i.e. if the `check` variable were to change, then we'd need to produce a new set of random bytes for the new
    // invocation of random(). It is not enough to say "the third occurrence of (A, B, C) should correspond to this
    // byte" because the third occurrence might be a different random() invocation on every run. This would become
    // especially apparent in repro bugs.

    /**
     * Returns an object representing type information for the last retrieved byte.
     *
     * This function is not idempotent, as it also updates information about the byte offset.
     * For example, it may be called 4 times for generating an Int (once for each possible byte offset).
     */
    fun consumeNextStackTraceInfo(): StackTraceInfo {
        val s = choiceState!!
        return StackTraceInfo(s.stackTrace, ByteTypeInfo(s.currType, s.currOfs++, s.currBounds))
    }

    private fun delegateWrapper(choiceKind: ChoiceKind, bounds: Bounds? = null): Closeable {
        if (depth == 0) {
            choiceState = RandomChoiceState(
                    getCurrentStackTrace(),
                    choiceKind,
                    currBounds = bounds
            )
        }
        depth++
        return Closeable {
            depth--
            if (depth == 0) {
                choiceState = null
            }
        }
    }

    override fun nextBoolean(): Boolean {
        return delegateWrapper(ChoiceKind.BOOLEAN).use { super.nextBoolean() }
    }

    override fun nextBytes(bytes: ByteArray) {
        return delegateWrapper(ChoiceKind.BYTE_ARRAY).use { super.nextBytes(bytes) }
    }

    override fun nextBytes(count: Int): ByteArray {
        return delegateWrapper(ChoiceKind.BYTE_ARRAY).use { super.nextBytes(count) }
    }

    override fun nextDouble(): Double {
        return delegateWrapper(ChoiceKind.DOUBLE).use { super.nextDouble() }
    }

    override fun nextDouble(min: Double, max: Double): Double {
        return delegateWrapper(ChoiceKind.DOUBLE).use { super.nextDouble(min, max) }
    }

    override fun nextFloat(): Float {
        return delegateWrapper(ChoiceKind.FLOAT).use { super.nextFloat() }
    }

    override fun nextFloat(min: Float, max: Float): Float {
        return delegateWrapper(ChoiceKind.FLOAT).use { super.nextFloat(min, max) }
    }

    override fun nextGaussian(): Double {
        return delegateWrapper(ChoiceKind.DOUBLE).use { super.nextGaussian() }
    }

    override fun nextByte(min: Byte, max: Byte): Byte {
        return delegateWrapper(ChoiceKind.BYTE, Bounds(min.toInt(), max.toInt())).use { super.nextByte(min, max) }
    }

    override fun nextShort(min: Short, max: Short): Short {
        return delegateWrapper(ChoiceKind.SHORT, Bounds(min.toInt(), max.toInt())).use { super.nextShort(min, max) }
    }

    override fun nextChar(min: Char, max: Char): Char {
        return delegateWrapper(ChoiceKind.CHAR, Bounds(min.toInt(), max.toInt())).use { super.nextChar(min, max) }
    }

    override fun nextInt(): Int {
        return delegateWrapper(ChoiceKind.INT).use { super.nextInt() }
    }

    override fun nextInt(n: Int): Int {
        return delegateWrapper(ChoiceKind.INT).use { super.nextInt(n) }
    }

    override fun nextInt(min: Int, max: Int): Int {
        return delegateWrapper(ChoiceKind.INT, Bounds(min, max)).use { super.nextInt(min, max) }
    }

    override fun nextLong(): Long {
        return delegateWrapper(ChoiceKind.LONG).use { super.nextLong() }
    }

    override fun nextLong(min: Long, max: Long): Long {
        return delegateWrapper(ChoiceKind.LONG).use { super.nextLong(min, max) }
    }

    override fun <T : Any?> choose(items: Array<out T>): T {
        return delegateWrapper(ChoiceKind.CHOOSE, Bounds(0, items.size)).use { super.choose(items) }
    }

    override fun <T : Any?> choose(items: MutableCollection<T>): T {
        return delegateWrapper(ChoiceKind.CHOOSE, Bounds(0, items.size)).use { super.choose(items) }
    }
}