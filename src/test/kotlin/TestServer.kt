import com.pholser.junit.quickcheck.generator.Generator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.StringReader
import java.nio.file.Paths
import kotlin.concurrent.thread

/**
 * Sets up boilerplate needed to run a test on a Server instance.
 */
fun <T> testServer(gen: Generator<T>,
                   testClassName: String,
                   testMethod: String,
                   testFn: (Server<T>) -> Unit) {
    // Initialize the server on the secondary thread to allow the job queue to be happy
    val server: Server<T> = Server(gen, testClassName, testMethod)
    server.isUnderUnitTest = true
    val t = thread {
        server.start()
    }
    // Do body of work, then terminate server
    testFn(server)
    server.stop()
    t.join()
}

// === FILE SYSTEM UTILITIES ===
fun <T> Server<T>.newSavedInput(name: String, bytes: ByteArray) {
    val f = Paths.get(this.saveInputDir.path, name).toFile()
    f.writeBytes(bytes)
    f.deleteOnExit()
}

fun <T> Server<T>.newSavedSession(name: String, history: FuzzHistory) {
    val f = Paths.get(this.saveSessionDir.path, name).toFile()
    f.writeText(Json.encodeToString(history))
    f.deleteOnExit()
}

// === FAKE REQUEST UTILITIES ===
fun <T> Server<T>.getResponseHandler(name: String): ResponseHandler = this.responseHandlers[name]
        ?: error("couldn't find response handler $name")

fun ResponseHandler.postString(msg: String): String = this.onPost(StringReader(msg).buffered())
inline fun <reified T> ResponseHandler.postJson(obj: T): String
    = this.onPost(StringReader(Json.encodeToString(obj)).buffered())

fun ResponseHandler.patchString(msg: String): String = this.onPatch(StringReader(msg).buffered())
inline fun <reified T> ResponseHandler.patchJson(obj: T): String
        = this.onPatch(StringReader(Json.encodeToString(obj)).buffered())
