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

// still need to implement: nextBigInteger, nextInstant, nextDuration
class AnnotatingRandomSource(delegate: StreamBackedRandom) : FastSourceOfRandomness(delegate) {
    // Keep track of which function actually was the top level
    var depth = 0
    private var choiceState: RandomChoiceState? = null

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
                    Thread.currentThread().stackTrace.map { it.toLine() },
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

    override fun <T : Any?> choose(items: Array<out T>?): T {
        return delegateWrapper(ChoiceKind.CHOOSE).use { super.choose(items) }
    }

    override fun <T : Any?> choose(items: MutableCollection<T>?): T {
        return delegateWrapper(ChoiceKind.CHOOSE).use { super.choose(items) }
    }
}