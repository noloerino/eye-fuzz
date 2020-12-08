import kotlinx.serialization.Serializable

typealias LocIndex = Int
typealias Choice = Int

/**
 * Because stack traces are not unique if you have two function calls on the same line, we need to add
 * a count variable to track ints. This isn't as strong as Zest ExecutionIndexing, but it's a weak substitute.
 *
 * If count is 0, that means this was the first occurrence of the provided stack trace on this run.
 */
@Serializable
data class StackTraceInfo(val stackTrace: StackTrace, val typeInfo: ByteTypeInfo, val count: Int)

/**
 * Allows for compression of stack traces and type info by storing integer indices instead of the full data every time.
 * LinkedSet rather than a list allows efficient lookup while maintaining efficient iteration.
 *
 * This list is global across the duration of a fuzzing session.
 */
val locList = linkedSetOf<StackTraceInfo>()

typealias ChoiceMap = LinkedHashMap<StackTraceInfo, Choice>

/**
 * Represents location choice data which is given back from POST/PATCH requests.
 */
@Serializable
data class LocFromPatch(val index: LocIndex, val choice: Choice)

/**
 * Represents data for a program location (stack trace) that gets displayed on the frontend.
 * This should be returned as part of a list, where the index in the list corresponds to the LocIndex for this element.
 */
@Serializable
data class LocWithData(val stackTraceInfo: StackTraceInfo,
                       val choice: Choice,
                       val used: Boolean)

typealias StackTrace = List<StackTraceLine>

/**
 * Converts a java.lang.StackTraceElement into a custom data type for easy serialization.
 */
fun StackTraceElement.toLine(): StackTraceLine = StackTraceLine(
        this.className,
        this.fileName,
        this.lineNumber,
        this.methodName,
)

/**
 * A "stack trace with counts," very much like Zest's ExecutionIndex. Unlike ExecutionIndex, a new stack trace is
 * recorded only when an invocation to random() occurs, not on every byte produced by the randomness source.
 */
@Serializable
data class StackTraceLine(
        val className: String,
        val fileName: String?,
        val lineNumber: Int,
        val methodName: String,
) {
    override fun toString(): String = "$className.$methodName($fileName:$lineNumber)"
}

