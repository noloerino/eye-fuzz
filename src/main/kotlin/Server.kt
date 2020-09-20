import com.pholser.junit.quickcheck.generator.GenerationStatus
import com.pholser.junit.quickcheck.random.SourceOfRandomness
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex
import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.NonTrackingGenerationStatus
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop
import edu.berkeley.cs.jqf.instrument.tracing.events.BranchEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.locks.ReentrantLock

/**
 * Manages the HTTP connection to dispatch the generator and fuzzer as needed.
 *
 * Since we only have one server at a time, everything is static. Yay.
 */
object Server {
    /** Tracks the random value stored at an EI, as well as the last line of the stack trace  */
    private val rng = Random()
    private val genGuidance = EiManualMutateGuidance(rng)
    private val jsGen = JavaScriptCodeGenerator()
    private var genContents: String? = null

    @JvmField
    val eventStrings: MutableMap<Int, String> = HashMap()

    private val lock = ReentrantLock()
    private val hasEiUpdate = lock.newCondition()
    private val hasFinishedUpdate = lock.newCondition()

    @JvmStatic
    fun main(args: Array<String>) {
        init()
        val server = HttpServer.create(InetSocketAddress("localhost", 8000), 0)
        server.createContext("/ei", object : ResponseHandler("ei") {
            override fun onGet(): String {
                // For ease of parsing, we send the random byte followed by the last event string, followed by
                // the execution index; each item separated by a colon.
                val sb = StringBuilder()
                for ((ei, value) in genGuidance.eiMap) {
                    sb.append(value.choice)
                    sb.append(":")
                    sb.append(value.stackTrace)
                    sb.append(":")
                    sb.append(ei.toString())
                    sb.append("\n")
                }
                return sb.toString()
            }

            override fun onPost(reader: BufferedReader): String {
                // POST expects parameters in a similar fashion, i.e. each line is int, space, EI array
                val newEiMap = LinkedHashMap<ExecutionIndex, EiData>()
                var line = reader.readLine()
                while (line != null) {
                    val chunks = line.replace(",", "")
                            .replace("[", "")
                            .replace("]", "")
                            .split(" ").toTypedArray()
                    val data = chunks[0].toInt()
                    val eiArr = IntArray(chunks.size - 1)
                    for (i in 1 until chunks.size) {
                        eiArr[i - 1] = chunks[i].trim { it <= ' ' }.toInt()
                    }
                    val key = ExecutionIndex(eiArr)
                    newEiMap[key] = EiData(genGuidance.eiMap[key]!!.stackTrace, data)
                    line = reader.readLine()
                }
                genGuidance.eiMap = newEiMap
                return "OK"
            }
        })
        server.createContext("/coverage",
                object : ResponseHandler("coverage") {
                    override fun onGet(): String {
                        return ""
                    }
                }
        )
        val genHandler = GenHandler("generator")
        server.createContext("/generator", genHandler)
        server.start()
        println("Server initialized at port " + server.address.port)
        while (true) {
            lock.lock()
            try {
                while (!genHandler.newEiUpdate) {
                    hasEiUpdate.await()
                }
                dummy()
                genHandler.newEiUpdate = false
                hasFinishedUpdate.signal()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun init() {
        System.setProperty("jqf.traceGenerators", "true")
        SingleSnoop.setCallbackGenerator { thread: Thread? -> genGuidance.generateCallBack(thread) }
        //        String target = JavaScriptCodeGenerator.class.getName() + "#generate";
        val target = Server::class.java.name + "#dummy"
        SingleSnoop.startSnooping(target)
        println(SingleSnoop.entryPoints)
        // Needed for some jank call tracking
        dummy()
        println("Initial map is of size " + genGuidance.eiMap.size)
    }

    /**
     * Serves as an entry point to the tracking of the EI call stack.
     * Returns should probably stop tracking at runGenerator to avoid an oob exception.
     */
    private fun dummy() {
        genGuidance.reset()
        runGenerator()
    }

    private fun getGenContents(): String {
        return genContents!!.substring(0, Math.min(genContents!!.length, 1024))
    }

    /**
     * Reruns the generator to update the generator string.
     * NOT ACTUALLY THE ENTRY POINT - USE dummy INSTEAD
     *
     * See Zest fuzzing loop.
     * https://github.com/rohanpadhye/JQF/blob/0152e82d4eb414b06438dec3ef0322135318291a/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java#L159
     */
    private fun runGenerator() {
        val randomFile = StreamBackedRandom(genGuidance.input, java.lang.Long.BYTES)
        val random: SourceOfRandomness = FastSourceOfRandomness(randomFile)
        val genStatus: GenerationStatus = NonTrackingGenerationStatus(random)
        genContents = jsGen.generate(random, genStatus)
        println("generator produced: " + getGenContents())
    }

    @JvmStatic
    fun eventToString(e: TraceEvent): String {
        return if (e is BranchEvent) {
            val b = e
            eventStrings.computeIfAbsent(
                    b.iid
            ) { _k: Int? ->
                String.format("(branch) %s#%s()@%d [%d]", b.containingClass, b.containingMethodName,
                        b.lineNumber, b.arm)
            }
        } else if (e is CallEvent) {
            val c = e
            eventStrings.computeIfAbsent(
                    c.iid
            ) { _k: Int? ->
                String.format("(call) %s#%s()@%d --> %s", c.containingClass, c.containingMethodName,
                        c.lineNumber, c.invokedMethodName)
            }
        } else {
            eventStrings.computeIfAbsent(
                    e.iid
            ) { _k: Int? ->
                String.format("(other) %s#%s()@%d", e.containingClass, e.containingMethodName,
                        e.lineNumber)
            }
        }
    }

    private class GenHandler(name: String?) : ResponseHandler(name) {
        var newEiUpdate = false
        override fun onGet(): String {
            return getGenContents()
        }

        override fun onPost(reader: BufferedReader): String {
            // Need to yield back to main thread, which is running a loop with a monitor
            // that just runs the generator when notified
            lock.lock()
            try {
                newEiUpdate = true
                hasEiUpdate.signal()
            } finally {
                lock.unlock()
            }
            lock.lock()
            try {
                while (newEiUpdate) {
                    hasFinishedUpdate.await()
                }
            } finally {
                lock.unlock()
            }
            println("Updated generator contents (map is of size " + genGuidance.eiMap.size + ")")
            return getGenContents()
        }
    }

    private abstract class ResponseHandler(private val name: String?) : HttpHandler {
        override fun handle(httpExchange: HttpExchange) {
            val method = httpExchange.requestMethod
            val headers = httpExchange.responseHeaders
            //            System.out.println(method + " /" + name);
            headers.add("Access-Control-Allow-Origin", "*")
            headers.add("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
            //            headers.add("Access-Control-Allow-Headers","Access-Control-Allow-Origin,Content-Type");
            var response = ""
            when (method) {
                "GET" -> {
                    response = onGet()
                    if (response.length == 0) {
                        httpExchange.sendResponseHeaders(204, -1)
                    } else {
                        httpExchange.sendResponseHeaders(200, response.length.toLong())
                    }
                }
                "POST" -> {
                    BufferedReader(InputStreamReader(httpExchange.requestBody)).use { reader -> response = onPost(reader) }
                    if (response.length == 0) {
                        httpExchange.sendResponseHeaders(204, -1)
                    } else {
                        httpExchange.sendResponseHeaders(200, response.length.toLong())
                    }
                }
                "OPTIONS" -> {
                    httpExchange.sendResponseHeaders(204, -1)
                    httpExchange.sendResponseHeaders(501, 0)
                }
                else -> httpExchange.sendResponseHeaders(501, 0)
            }
            httpExchange.responseBody.use { out -> out.write(response.toByteArray()) }
            httpExchange.close()
        }

        /**
         * @return the string to be written as a response
         */
        open fun onGet(): String {
            return ""
        }

        /**
         * @param reader the request body
         * @return the string to be written as a response
         */
        open fun onPost(reader: BufferedReader): String {
            return ""
        }
    }
}