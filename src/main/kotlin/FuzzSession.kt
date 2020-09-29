import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import kotlinx.serialization.Serializable
import java.util.*

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

    private val diffs = mutableListOf<EiDiff>()
    // hides mutability of diffs
    val history: List<EiDiff> get() = diffs

    fun resetForNewRun() {
        usedThisRun.clear()
    }

    fun clear() {
        eiMap.clear()
        usedThisRun.clear()
        diffs.clear()
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

@Serializable
sealed class EiDiff {
    @Serializable
    object ClearAllUsed : EiDiff()

    @Serializable
    class MarkUsed(val ei: @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex) : EiDiff()

    @Serializable
    class UpdateChoice(val ei: @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex,
                       val new: Int) : EiDiff()
    @Serializable
    class Create(val ei: @Serializable(with = ExecutionIndexSerializer::class) ExecutionIndex,
                 val stackTrace: StackTrace, val choice: Int) : EiDiff()

    fun apply(state: FuzzState) {
        when (this) {
            is ClearAllUsed -> state.usedThisRun.clear()
            is MarkUsed -> state.usedThisRun.add(ei)
            is UpdateChoice -> state.eiMap.replace(ei, EiData(state.eiMap[ei]!!.stackTrace, new))
            is Create -> state.eiMap[ei] = EiData(stackTrace, choice)
        }
    }
}

