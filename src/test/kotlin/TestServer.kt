import com.pholser.junit.quickcheck.generator.Generator
import kotlin.concurrent.thread

/**
 * Sets up boilerplate needed to run a test on a Server instance.
 */
fun <T> testServer(gen: Generator<T>,
                   testClassName: String,
                   testMethod: String,
                   testFn: (Server<T>) -> Unit) {
    val server = Server(gen, testClassName, testMethod)
    server.underUnitTest = true
    // Let server run on separate thread so we're not blocked on it forever
    val t = thread {
        server.start()
    }
    // Do body of work, then terminate server
    testFn(server)
    server.stop()
    t.join()
}