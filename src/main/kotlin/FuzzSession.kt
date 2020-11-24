import kotlinx.serialization.Serializable
import java.util.*

@Serializable
data class FuzzHistory(val locList: List<StackTraceInfo>, val runResults: List<RunResult>)

class FuzzState(private val guidance: EiManualMutateGuidance, private val rng: Random) {
    /**
     * Tracks which EIs were used in the most recent run of the generator.
     *
     * Since EIs are unique (as visiting the same location twice would result in an incremented count),
     * we're able to use a LinkedSet instead of an ordinary list.
     */
    val usedThisRun = linkedSetOf<StackTraceInfo>()
    var choiceMap: ChoiceMap = linkedMapOf()
    val mapSize get() = choiceMap.size

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
    val history: FuzzHistory get() {
        return FuzzHistory(locList.toList(), diffStack)
    }

    fun resetForNewRun() {
        usedThisRun.clear()
        diffStack.add(RunResult())
    }

    fun clear() {
        choiceMap.clear()
        usedThisRun.clear()
        diffStack.clear()
        locList.clear()
    }

    fun reloadFromHistory(newHistory: FuzzHistory) {
        clear()
        locList.addAll(newHistory.locList)
        newHistory.runResults.forEach { runResult ->
            diffStack.add(runResult)
            runResult.applyUpdate(this)
        }
    }

    /**
     * Adds the EI to the map if not present, and returns the choice made at this EI.
     */
    fun add(stackTraceInfo: StackTraceInfo): Int {
        usedThisRun.add(stackTraceInfo)
        currRunResult.markUsed(stackTraceInfo)
        // Attempt to get a value from the map, or else generate a random value
        return choiceMap.computeIfAbsent(stackTraceInfo) {
            val choice = guidance.reproValues?.next() ?: rng.nextInt(256)
            // TODO handle case of repro, where this may actually be an update rather than create
            currRunResult.createChoice(stackTraceInfo, choice)
            choice
        }
    }

    /**
     * Updates the choice corresponding to the stack trace with this value.
     * Because this is exposed to the HTTP API, it takes an index as argument.
     */
    fun update(idx: LocIndex, choice: Int) {
        val sti = locList.elementAt(idx)
        currRunResult.updateChoice(sti, choiceMap[sti]!!, choice)
        choiceMap[sti] = choice
    }
    
    fun snapshot(): List<LocWithData> = choiceMap.map { (st, value) ->
        LocWithData(st, value, st in usedThisRun)
    }
}

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
     * Looks up the int associated with a particular stack trace, assigning a new one if necessary.
     */
    private fun lookupOrStore(newInfo: StackTraceInfo): LocIndex {
        val idx = locList.indexOf(newInfo)
        return if (idx == -1) {
            locList.add(newInfo)
            locList.size - 1
        } else {
            idx
        }
    }

    @Serializable
    data class UpdateChoice(val locIndex: LocIndex, val old: Int, val new: Int)

    @Serializable
    data class CreateChoice(val locIndex: LocIndex, val new: Int)

    var serializedResult: String = ""
    private val markedUsed = mutableSetOf<LocIndex>()
    private val createChoices = mutableListOf<CreateChoice>()
    private val updateChoices = mutableListOf<UpdateChoice>()

    fun markUsed(stackTraceInfo: StackTraceInfo) {
        markedUsed.add(lookupOrStore(stackTraceInfo))
    }

    fun updateChoice(stackTraceInfo: StackTraceInfo, old: Int, choice: Int) {
        updateChoices.add(UpdateChoice(lookupOrStore(stackTraceInfo), old, choice))
    }

    fun createChoice(stackTraceInfo: StackTraceInfo, choice: Int) {
        createChoices.add(CreateChoice(lookupOrStore(stackTraceInfo), choice))
    }

    fun applyUpdate(state: FuzzState) {
        markedUsed.forEach { state.usedThisRun.add(locList.elementAt(it)) }
        createChoices.forEach { (i, choice) -> state.choiceMap[locList.elementAt(i)] = choice }
        updateChoices.forEach { (i, _, new) -> state.choiceMap[locList.elementAt(i)] = new }
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
