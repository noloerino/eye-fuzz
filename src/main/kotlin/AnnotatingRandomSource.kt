import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness
import java.io.Closeable

enum class ChoiceKind {
    BOOLEAN,
    BYTE,
    BYTE_ARRAY,
    CHAR,
    CHOOSE,
    DOUBLE,
    FLOAT,
    INT,
    LONG,
    SHORT
}

// still need to implement: nextBigInteger, nextInstant, nextDuration
class AnnotatingRandomSource(delegate: StreamBackedRandom) : FastSourceOfRandomness(delegate) {
    // Keep track of which function actually was the top level
    var depth = 0
    var currType: ChoiceKind? = null
    private fun delegateWrapper(choiceKind: ChoiceKind): Closeable {
        if (depth == 0) {
            currType = choiceKind
        }
        depth++
        return Closeable {
            depth--
            if (depth == 0) {
                currType = null
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
        return delegateWrapper(ChoiceKind.BYTE).use { super.nextByte(min, max) }
    }

    override fun nextShort(min: Short, max: Short): Short {
        return delegateWrapper(ChoiceKind.SHORT).use { super.nextShort(min, max) }
    }

    override fun nextChar(min: Char, max: Char): Char {
        return delegateWrapper(ChoiceKind.CHAR).use { super.nextChar(min, max) }
    }

    override fun nextInt(): Int {
        return delegateWrapper(ChoiceKind.INT).use { super.nextInt() }
    }

    override fun nextInt(n: Int): Int {
        return delegateWrapper(ChoiceKind.INT).use { super.nextInt(n) }
    }

    override fun nextInt(min: Int, max: Int): Int {
        return delegateWrapper(ChoiceKind.INT).use { super.nextInt(min, max) }
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