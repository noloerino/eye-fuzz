import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

data class EiData(val stackTrace: StackTrace, var choice: Int)

/**
 * Represents an execution index without a stack trace, which is what is given back from POST/PATCH requests.
 */
@Serializable
data class EiWithoutStackTrace(val ei: SerializableEi, val choice: Int)

/**
 * Represents data for an execution index that gets displayed on the frontend.
 */
@Serializable
data class EiWithData(val ei: SerializableEi,
                      val stackTrace: StackTrace,
                      val choice: Int,
                      val used: Boolean)

typealias StackTrace = List<StackTraceLine>

/**
 * Represents a single line within a stack trace.
 */
@Serializable
data class StackTraceLine(val callLocation: CallLocation, val count: Int)

@Serializable
data class CallLocation(val iid: Int, val containingClass: String, val containingMethodName: String,
                        val lineNumber: Int, val invokedMethodName: String)

object ExecutionIndexSerializer : KSerializer<ExecutionIndex> {
    override val descriptor = ListSerializer(Int.serializer()).descriptor

    override fun deserialize(decoder: Decoder): ExecutionIndex {
        return ExecutionIndex(ListSerializer(Int.serializer()).deserialize(decoder).toIntArray())
    }

    override fun serialize(encoder: Encoder, value: ExecutionIndex) {
        val arr = value.toString()
                .replace("[", "")
                .replace("]", "")
                .split(",")
                .map { it.trim().toInt()}
        // Even though only the even indices represent actual program locations, we still need to pass counts
        // for serialization purposes
        ListSerializer(Int.serializer()).serialize(encoder, arr)
    }
}

typealias SerializableEi = @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex
