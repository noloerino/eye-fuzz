import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class FuzzHistory(val runResults: List<RunResult>)

class FuzzState(private val guidance: EiManualMutateGuidance, private val rng: Random) {
    /**
     * Tracks which EIs were used in the most recent run of the generator.
     *
     * Since EIs are unique (as visiting the same location twice would result in an incremented count),
     * we're able to use a LinkedSet instead of an ordinary list.
     */
    val usedThisRun = linkedSetOf<ExecutionIndex>()
    var eiMap = linkedMapOf<ExecutionIndex, EiData>()
    val mapSize get() = eiMap.size

    /**
     * Each element in the list contains the sequence of diffs incurred over the course of a single generator run.
     * The last element of the list represents the most recent set of changes.
     *
     * The initial element is an empty result.
     */
    private val diffStack = mutableListOf(RunResult())
    private val currRunResult: RunResult get() = diffStack.last()
    var genOutput get() = currRunResult.serializedResult
        set(v) { currRunResult.serializedResult = v }

    // hides mutability of diffs, and remove first sentinel node
    val history: FuzzHistory get() = FuzzHistory(diffStack)

    fun resetForNewRun() {
        usedThisRun.clear()
        diffStack.add(RunResult())
    }

    fun clear() {
        eiMap.clear()
        usedThisRun.clear()
        diffStack.clear()
    }

    fun reloadFromHistory(newHistory: FuzzHistory) {
        clear()
        newHistory.runResults.forEach { runResult ->
            diffStack.add(runResult)
            runResult.applyUpdate(this)
        }
    }

    /**
     * Adds the EI to the map if not present, and returns the choice made at this EI.
     */
    fun add(ei: ExecutionIndex, typeInfo: ByteTypeInfo): Int {
        usedThisRun.add(ei)
        currRunResult.markUsed(ei)
        // Attempt to get a value from the map, or else generate a random value
        return eiMap.computeIfAbsent(ei) {
            val choice = guidance.reproValues?.next() ?: rng.nextInt(256)
            // TODO handle case of repro, where this may actually be an update rather than create
            currRunResult.createChoice(ei, guidance.getFullStackTrace(), choice, typeInfo)
            EiData(guidance.getFullStackTrace(), typeInfo, choice)
        }.choice
    }

    /**
     * Updates the choice corresponding to the EI with this value.
     */
    fun update(ei: ExecutionIndex, choice: Int) {
        currRunResult.updateChoice(ei, eiMap[ei]!!.choice, choice)
        eiMap[ei]!!.choice = choice
    }
    
    fun snapshot(): List<EiWithData> = eiMap.map { (ei, value) ->
        EiWithData(ei, value.stackTrace, value.typeInfo, value.choice, ei in usedThisRun)
    }
}

typealias SerializableEi = @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex

/**
 * Encodes information about changes produced over the course of a run.
 * A new instance is blank, and should be mutated over the course of a single generator run.
 *
 * The updates are applied in the orders listed in the fields: used EI are marked first, followed by creation of new EI
 * followed by updates to existing EI.
 */
@Serializable
class RunResult {

    @Serializable
    data class UpdateChoice(val ei: SerializableEi, val old: Int, val new: Int)

    @Serializable
    data class CreateChoice(val ei: SerializableEi, val stackTrace: StackTrace, val typeInfo: ByteTypeInfo, val new: Int)

    var serializedResult: String = ""
    private val markedUsed = mutableSetOf<SerializableEi>()
    private val createChoices = mutableListOf<CreateChoice>()
    private val updateChoices = mutableListOf<UpdateChoice>()

    fun markUsed(ei: ExecutionIndex) {
        markedUsed.add(ei)
    }

    fun updateChoice(ei: ExecutionIndex, old: Int, choice: Int) {
        updateChoices.add(UpdateChoice(ei, old, choice))
    }

    fun createChoice(ei: ExecutionIndex, stackTrace: StackTrace, choice: Int, typeInfo: ByteTypeInfo) {
        createChoices.add(CreateChoice(ei, stackTrace, typeInfo, choice))
    }

    fun applyUpdate(state: FuzzState) {
        markedUsed.forEach { state.usedThisRun.add(it) }
        createChoices.forEach {
            (ei, stackTrace, typeInfo, choice) -> state.eiMap[ei] = EiData(stackTrace, typeInfo, choice)
        }
        updateChoices.forEach { (ei, choice) -> state.eiMap[ei]!!.choice = choice }
    }

    fun copy(): RunResult {
        val other = RunResult()
        other.markedUsed.addAll(markedUsed)
        Collections.addAll(other.updateChoices, *updateChoices.toTypedArray())
        Collections.addAll(other.createChoices, *createChoices.toTypedArray())
        return other
    }

    override fun equals(other: Any?): Boolean = other is RunResult
            && markedUsed == other.markedUsed
            && updateChoices == other.updateChoices
            && createChoices == other.createChoices

    override fun hashCode(): Int {
        var result = markedUsed.hashCode()
        result = 31 * result + updateChoices.hashCode()
        result = 31 * result + createChoices.hashCode()
        return result
    }
}
