import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

@Serializable
data class FuzzHistory(val eiList: List<CompressedEiKey>, val runResults: List<RunResult>)

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
    val history: FuzzHistory get() = FuzzHistory(eiList, diffStack)

    fun resetForNewRun() {
        usedThisRun.clear()
        diffStack.add(RunResult())
    }

    fun clear() {
        eiMap.clear()
        usedThisRun.clear()
        diffStack.clear()
        eiList.clear()
    }

    fun reloadFromHistory(newHistory: FuzzHistory) {
        clear()
        eiList.addAll(newHistory.eiList)
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
        val stackTrace = guidance.getFullStackTrace()
        currRunResult.markUsed(ei, stackTrace)
        // Attempt to get a value from the map, or else generate a random value
        return eiMap.computeIfAbsent(ei) {
            val choice = guidance.reproValues?.next() ?: rng.nextInt(256)
            // TODO handle case of repro, where this may actually be an update rather than create
            currRunResult.createChoice(ei, stackTrace, choice, typeInfo)
            EiData(guidance.getFullStackTrace(), typeInfo, choice)
        }.choice
    }

    /**
     * Updates the choice corresponding to the EI with this value.
     */
    fun update(ei: ExecutionIndex, choice: Int) {
        currRunResult.updateChoice(ei, guidance.getFullStackTrace(), eiMap[ei]!!.choice, choice)
        eiMap[ei]!!.choice = choice
    }
    
    fun snapshot(): List<EiWithData> = eiMap.map { (ei, value) ->
        EiWithData(ei, value.stackTrace, value.typeInfo, value.choice, ei in usedThisRun)
    }
}

typealias EiIndex = Int

@Serializable
data class CompressedEiKey(val ei: SerializableEi, val stackTrace: StackTrace)

/**
 * Allows for compression of EIs by storing integer indices instead of the full EI.
 */
val eiList: MutableList<CompressedEiKey> = mutableListOf()

/**
 * Encodes information about changes produced over the course of a run.
 * A new instance is blank, and should be mutated over the course of a single generator run.
 *
 * The updates are applied in the orders listed in the fields: used EI are marked first, followed by creation of new EI
 * followed by updates to existing EI.
 */
@Serializable
class RunResult {
    /**
     * Looks up the int associated with a particular EI, assigning a new one if necessary.
     */
    private fun lookupOrStore(ei: ExecutionIndex, stackTrace: StackTrace): EiIndex {
        for ((i, compressedEiKey) in eiList.withIndex()) {
            if (ei == compressedEiKey.ei) {
                return i
            }
        }
        eiList.add(CompressedEiKey(ei, stackTrace))
        return eiList.size - 1
    }

    @Serializable
    data class UpdateChoice(val eiIndex: EiIndex, val old: Int, val new: Int)

    @Serializable
    data class CreateChoice(val eiIndex: EiIndex, val typeInfo: ByteTypeInfo, val new: Int)

    var serializedResult: String = ""
    private val markedUsed = mutableSetOf<EiIndex>()
    private val createChoices = mutableListOf<CreateChoice>()
    private val updateChoices = mutableListOf<UpdateChoice>()

    fun markUsed(ei: ExecutionIndex, stackTrace: StackTrace) {
        markedUsed.add(lookupOrStore(ei, stackTrace))
    }

    fun updateChoice(ei: ExecutionIndex, stackTrace: StackTrace, old: Int, choice: Int) {
        updateChoices.add(UpdateChoice(lookupOrStore(ei, stackTrace), old, choice))
    }

    fun createChoice(ei: ExecutionIndex, stackTrace: StackTrace, choice: Int, typeInfo: ByteTypeInfo) {
        createChoices.add(CreateChoice(lookupOrStore(ei, stackTrace), typeInfo, choice))
    }

    fun applyUpdate(state: FuzzState) {
        markedUsed.forEach { state.usedThisRun.add(eiList[it].ei) }
        createChoices.forEach {
            (i, typeInfo, choice) -> state.eiMap[eiList[i].ei] = EiData(eiList[i].stackTrace, typeInfo, choice)
        }
        updateChoices.forEach { (i, old, new) -> state.eiMap[eiList[i].ei]!!.choice = new }
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
