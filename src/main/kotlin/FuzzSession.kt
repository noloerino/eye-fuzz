import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import kotlinx.serialization.Serializable
import java.util.*

// TODO need to store initial state (or current state as well), since initial state is NOT empty
typealias FuzzHistory = List<List<EiDiff>>

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
     */
    private val diffStack = mutableListOf<MutableList<EiDiff>>()
    // resetForNewRun() must be called first, or else this will throw an exception
    private val diffs: MutableList<EiDiff> get() = diffStack.last()

    // hides mutability of diffs
    val history: FuzzHistory get() = diffStack

    fun resetForNewRun() {
        usedThisRun.clear()
        diffStack.add(mutableListOf())
    }

    fun clear() {
        eiMap.clear()
        usedThisRun.clear()
        diffStack.clear()
    }

    fun reloadFromDiffs(newDiffStack: List<List<EiDiff>>) {
        clear()
        newDiffStack.forEach { lst ->
            diffStack.add(lst.toMutableList())
            lst.forEach {
                diffs.add(it)
                it.apply(this)
            }
        }
    }

    /**
     * Adds the EI to the map if not present, and returns the choice made at this EI.
     */
    fun add(ei: ExecutionIndex): Int {
        usedThisRun.add(ei)
        diffs.add(EiDiff.MarkUsed(ei))
        // Attempt to get a value from the map, or else generate a random value
        return eiMap.computeIfAbsent(ei) {
            val choice = guidance.reproValues?.next() ?: rng.nextInt(256)
            diffs.add(EiDiff.Create(ei, guidance.getFullStackTrace(), choice))
            EiData(guidance.getFullStackTrace(), choice)
        }.choice
    }

    /**
     * Updates the choice corresponding to the EI with this value.
     */
    fun update(ei: ExecutionIndex, choice: Int) {
        diffs.add(EiDiff.UpdateChoice(ei, choice))
        eiMap[ei]!!.choice = choice
    }
    
    fun snapshot(): List<EiWithData> = eiMap.map { (ei, value) ->
        EiWithData(ei, value.stackTrace, value.choice, ei in usedThisRun)
    }
}

// TODO instead of storing as a sequence of changes, it's more efficient to make each fuzz rerun contain a field
// for each type of change, e.g. a list/bitset of used, a list of updates, and a list of creations
@Serializable
sealed class EiDiff {
    @Serializable
    data class MarkUsed(val ei: @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex) : EiDiff()

    @Serializable
    data class UpdateChoice(val ei: @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex,
                       val new: Int) : EiDiff()
    @Serializable
    data class Create(val ei: @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex,
                 val stackTrace: StackTrace, val choice: Int) : EiDiff()

    fun apply(state: FuzzState) {
        when (this) {
            is MarkUsed -> state.usedThisRun.add(ei)
            is UpdateChoice -> state.eiMap.replace(ei, EiData(state.eiMap[ei]!!.stackTrace, new))
            is Create -> state.eiMap[ei] = EiData(stackTrace, choice)
        }
    }
}

