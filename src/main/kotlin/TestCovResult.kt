import edu.berkeley.cs.jqf.fuzz.guidance.Result
import kotlinx.serialization.Serializable

@Serializable
data class TestCovResult(val status: Result, val events: List<String>)