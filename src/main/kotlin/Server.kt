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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
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

    private val eventStrings: MutableMap<Int, String> = mutableMapOf()

    @JvmField
    val callLocations: MutableMap<Int, CallLocation> = mutableMapOf()

    private val lock = ReentrantLock()
    private val hasEiUpdate = lock.newCondition()
    private val hasFinishedUpdate = lock.newCondition()

    @JvmStatic
    fun main(args: Array<String>) {
        init()
        val server = HttpServer.create(InetSocketAddress("localhost", 8000), 0)
        server.createContext("/ei", object : ResponseHandler("ei") {
            /**
             * GET returns a list of all EIs in the map.
             */
            override fun onGet(): String {
                val data = genGuidance.eiMap.map { (ei, value) ->
                    EiWithData(ei, value.stackTrace, value.choice, ei in genGuidance.usedThisRun)
                }
                return Json.encodeToString(data)
            }

            /**
             * PATCH performs a full update to EIs, removing any EIs not included in this request.
             */
            override fun onPost(reader: BufferedReader): String {
                val newEiMap = LinkedHashMap<ExecutionIndex, EiData>()
                val text = reader.readText()
                val arr = Json.decodeFromString<List<EiWithoutStackTrace>>(text)
                for (e in arr) {
                    val key = e.ei
                    val choice = e.choice
                    newEiMap[key] = EiData(genGuidance.eiMap[key]!!.stackTrace, choice)
                }
                genGuidance.eiMap = newEiMap
                return "OK"
            }

            /**
             * PATCH performs a partial update to EIs, leaving ones that weren't included in the request untouched.
             */
            override fun onPatch(reader: BufferedReader): String {
                val text = reader.readText()
                val arr = Json.decodeFromString<List<EiWithoutStackTrace>>(text)
                for (e in arr) {
                    val key = e.ei
                    genGuidance.eiMap[key]!!.choice = e.choice
                }
                return "OK"
            }
        })
        server.createContext("/save", SaveHandler())
//        server.createContext("/coverage",
//                object : ResponseHandler("coverage") {
//                    override fun onGet(): String {
//                        return ""
//                    }
//                }
//        )
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
        SingleSnoop.setCallbackGenerator(genGuidance::generateCallBack)
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
        return genContents!!.substring(0, genContents!!.length.coerceAtMost(1024))
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
        return when (e) {
            is BranchEvent -> {
                eventStrings.computeIfAbsent(e.iid) {
                    String.format("(branch) %s#%s()@%d [%d]", e.containingClass, e.containingMethodName,
                            e.lineNumber, e.arm)
                }
            }
            is CallEvent -> {
                callLocations.computeIfAbsent(e.iid) {
                    CallLocation(e.iid, e.containingClass, e.containingMethodName, e.lineNumber, e.invokedMethodName)
                }
                eventStrings.computeIfAbsent(e.iid) {
                    String.format("(call) %s#%s()@%d --> %s", e.containingClass, e.containingMethodName,
                            e.lineNumber, e.invokedMethodName)
                }
            }
            else -> {
                eventStrings.computeIfAbsent(e.iid) {
                    String.format("(other) %s#%s()@%d", e.containingClass, e.containingMethodName, e.lineNumber)
                }
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

    private class SaveHandler : ResponseHandler("save") {

        val saveDir = File("savedInputs")

        init {
            assert(saveDir.mkdirs())
        }

        @Serializable
        data class SaveRequest(val fileName: String)

        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val saveRequest = Json.decodeFromString<SaveRequest>(text)
            // just kinda assume the file exists I guess...
            val saveFile = saveDir.toPath().resolve(saveRequest.fileName).toFile()
            println("Saving last run to ${saveFile.canonicalPath}")
            genGuidance.writeLastRunToFile(saveFile)
            return "OK"
        }
    }

    private abstract class ResponseHandler(private val name: String?) : HttpHandler {

        private val verbose = true //false

        override fun handle(httpExchange: HttpExchange) {
            val method = httpExchange.requestMethod
            val headers = httpExchange.responseHeaders
            if (verbose) {
                println("$method /$name")
            }
            headers.add("Access-Control-Allow-Origin", "*")
            headers.add("Access-Control-Allow-Methods", "GET,POST,PATCH,OPTIONS")
            headers.add("Access-Control-Allow-Headers", "Access-Control-Allow-Origin,Content-Type")
            var response = ""
            fun endResponse() {
                if (response.isEmpty()) {
                    httpExchange.sendResponseHeaders(204, -1)
                } else {
                    httpExchange.sendResponseHeaders(200, response.length.toLong())
                }
            }
            when (method) {
                "GET" -> {
                    response = onGet()
                    endResponse()
                }
                "POST" -> {
                    BufferedReader(InputStreamReader(httpExchange.requestBody)).use { reader -> response = onPost(reader) }
                    endResponse()
                }
                "PATCH" -> {
                    BufferedReader(InputStreamReader(httpExchange.requestBody)).use { reader -> response = onPatch(reader) }
                    endResponse()
                }
                "OPTIONS" -> {
                    httpExchange.sendResponseHeaders(204, -1)
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

        /**
         * @param reader the request body
         * @return the string to be written as a response
         */
        open fun onPatch(reader: BufferedReader): String {
            return ""
        }
    }
}