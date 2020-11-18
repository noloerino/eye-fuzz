import kotlinx.serialization.Serializable

typealias LocIndex = Int
typealias Choice = Int

/**
 * Allows for compression of stack traces and type info by storing integer indices instead of the full data every time.
 * LinkedSet rather than a list allows efficient lookup while maintaining efficient iteration.
 *
 * This list is global across the duration of a fuzzing session.
 */
val locList = linkedSetOf<StackTraceInfo>()

typealias ChoiceMap = LinkedHashMap<StackTrace, Choice>

data class LocData(val stackTrace: StackTrace, var choice: Choice)

/**
 * Represents location choice data which is given back from POST/PATCH requests.
 */
@Serializable
data class LocFromPatch(val index: LocIndex, val choice: Choice)

/**
 * Represents data for a program location (stack trace) that gets displayed on the frontend.
 */
@Serializable
data class LocWithData(val stackTrace: StackTrace,
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
        this.methodName
)

@Serializable
data class StackTraceLine(val className: String, val fileName: String?, val lineNumber: Int, val methodName: String)
