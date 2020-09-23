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
import edu.berkeley.cs.jqf.instrument.tracing.TraceLogger
import edu.berkeley.cs.jqf.instrument.tracing.events.*
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

    @JvmField
    val eventStrings: MutableMap<Int, String> = mutableMapOf()

    @JvmField
    val callLocations: MutableMap<Int, CallLocation> = mutableMapOf()

    var mainThread: Thread? = null

    /**
     * Since the main thread is the only thread that can run the generator, threads of the HTTP server will have to
     * yield to the main thread by way of monitors in order to rerun the generator.
     *
     * The system is assumed to have a single writer (the main thread), but possibly multiple readers.
     */
    private enum class MainThreadTask {
        RERUN_GENERATOR,
        LOAD_FROM_FILE
        ;

        /**
         * Issues a request for the writer associated with this state to eventually run doWork.
         * The current thread becomes blocked until the task is completed.
         */
        fun requestWork() {
            log("Issued work request for $this")
            lock.lock()
            try {
                hasTask[this.ordinal] = true
                taskQ.add(this)
                writer.signal()
            } finally {
                lock.unlock()
            }
            // Between the above and below line, it is possible for another invocation for requestWork to slip in
            // Therefore, we must be maintain a task queue rather than a single state variable
            lock.lock()
            try {
                // The condition readers[i] is associated with the presence of task hasTask[i]
                // We cannot rely on the queue being empty in case another work request was snuck in
                // (strictly speaking, we should store ints instead of bools for this reason, but we're assuming
                // only one request of each kind can be made at a time (which might actually be wrong)
                while (hasTask[this.ordinal]) {
                    readers[this.ordinal].await()
                }
            } finally {
                lock.unlock()
            }
            log("Satisfied work request for $this")
        }

        /**
         * Should be called on the main thread to signal the completion of a job.
         */
        fun signalCompletion() {
            require(lock.isHeldByCurrentThread)
            require(Thread.currentThread() == mainThread)
            try {
                hasTask[this.ordinal] = false
                readers[this.ordinal].signal()
            } finally {
                lock.unlock()
            }
        }

        companion object {
            private var taskQ = mutableListOf<MainThreadTask>()
            private val lock = ReentrantLock()
            private val hasTask: MutableList<Boolean> = values().map { false }.toMutableList()
            private val readers = values().map { lock.newCondition() }
            private val writer = lock.newCondition()

            private const val verbose = true

            fun log(msg: String) {
                if (verbose) {
                    println(msg)
                }
            }

            /**
             * Causes the current thread to wait until a job is requested (i.e. the writer condition is signalled),
             * at which point control is returned to the main thread.
             *
             * Unfortunately, this cannot be restructured to perform the task and then automatically signal waiters,
             * as ExecutionIndex keeps track of a call stack. As such, the entry point to the generator must be invoked
             * at the same location as it initially is.
             *
             * Must be called on the writer thread.
             *
             * @return the requested task item
             * IMPORTANT: the lock is still held when this function is returned, so signal must be called eventually.
             */
            fun waitForJob(): MainThreadTask {
                require(mainThread != null)
                require(Thread.currentThread() == mainThread) { "Jobs must run on the main thread" }
                lock.lock()
                while (taskQ.isEmpty()) {
                    writer.await()
                }
                val task = taskQ.removeFirst()
                log("Servicing work request for $task (queue: $taskQ on thread ${Thread.currentThread()})")
                return task
            }
        }
    }

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
        server.createContext("/load", LoadHandler())
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
            val task = MainThreadTask.waitForJob()
            try {
                when (task) {
                    MainThreadTask.RERUN_GENERATOR -> dummy()
                    MainThreadTask.LOAD_FROM_FILE -> TODO()
                }
            } finally {
                task.signalCompletion()
            }
        }
    }

    private fun init() {
        mainThread = Thread.currentThread()
        System.setProperty("jqf.traceGenerators", "true")
//        SingleSnoop.setCallbackGenerator(genGuidance::generateCallBack)
        //        String target = JavaScriptCodeGenerator.class.getName() + "#generate";
//        val target = Server::class.java.name + "#dummy"
//        SingleSnoop.startSnooping(target)
//        println(SingleSnoop.entryPoints)
        // Needed for some jank call tracking
        dummy()
        println("Initial map is of size " + genGuidance.eiMap.size)
    }

    /**
     * Serves as an entry point to the tracking of the EI call stack.
     * Returns should probably stop tracking at runGenerator to avoid an oob exception.
     */
    private fun dummy() {
        TraceLogger.get().remove()
        val target = Server::class.java.name + "#runGenerator"
        SingleSnoop.setCallbackGenerator(genGuidance::generateCallBack)
        SingleSnoop.startSnooping(target)
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
        return eventStrings.computeIfAbsent(e.iid) {
            when (e) {
                is BranchEvent -> {
                    String.format("(branch) %s#%s()@%d [%d]", e.containingClass, e.containingMethodName,
                            e.lineNumber, e.arm)
                }
                is CallEvent -> {
                    callLocations.computeIfAbsent(e.iid) {
                        CallLocation(e.iid, e.containingClass, e.containingMethodName, e.lineNumber, e.invokedMethodName)
                    }
                    String.format("(call) %s#%s()@%d --> %s", e.containingClass, e.containingMethodName,
                            e.lineNumber, e.invokedMethodName)
                }
                is ReturnEvent -> {
                    "(return) ${e.containingClass}#${e.containingMethodName}"
                }
                is AllocEvent -> {
                    "(alloc) size ${e.size} ${e.containingClass}#${e.containingMethodName}()@${e.lineNumber}"
                }
                is ReadEvent -> {
                    "(read) ${e.field} in ${e.containingClass}#${e.containingMethodName}()@${e.lineNumber}"
                }
                else -> {
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
            MainThreadTask.RERUN_GENERATOR.requestWork()
            println("Updated generator contents (map is of size " + genGuidance.eiMap.size + ")")
            return getGenContents()
        }
    }

    val saveDir = File("savedInputs")
    init {
        assert(saveDir.mkdirs())
    }

    private class SaveHandler : ResponseHandler("save") {
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

    private class LoadHandler : ResponseHandler("load") {
        @Serializable
        data class LoadRequest(val fileName: String)

        override fun onGet(): String {
            return Json.encodeToString<List<String>>(saveDir.list()!!.toList())
        }

        /**
         * Replaces the current values in the EI map with the values in the specified file.
         */
        override fun onPost(reader: BufferedReader): String {
            // TODO handle error case i guess
            return ""
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