import com.pholser.junit.quickcheck.generator.GenerationStatus
import com.pholser.junit.quickcheck.random.SourceOfRandomness
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
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

    var mainThread: Thread? = null

    /**
     * Since the main thread is the only thread that can run the generator, threads of the HTTP server will have to
     * yield to the main thread by way of monitors in order to rerun the generator.
     *
     * The system is assumed to have a single writer (the main thread), but possibly multiple readers.
     */
    private enum class MainThreadTask {
        RERUN_GENERATOR {
            override fun job() {
                dummy()
            }
        },

        /**
         * This job must be requested within the context provided by genGuidance.reproWithFile.
         */
        LOAD_FROM_FILE {
            override fun job() {
                require(genGuidance.isInReproMode) { "Repro job was requested outside guidance repro mode"}
                dummy()
            }
        }
        ;

        abstract fun job()

        /**
         * Issues a request for the writer associated with this state to eventually run doWork.
         * The current thread becomes blocked until the task is completed.
         */
        fun requestWork() {
            log("Issued work request for $this from thread ${Thread.currentThread()}")
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
            log("Acknowledged completion of work request for $this on thread ${Thread.currentThread()}")
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
             * Causes the current thread to wait until a job is requested (i.e. the writer condition is signalled).
             * The task is then performed on the main thread, and upon completion, the thread's waiters are signalled.
             *
             * Must be called on the writer thread.
             *
             * @return the requested task item
             */
            fun waitForJob() {
                require(mainThread != null)
                require(Thread.currentThread() == mainThread) { "Jobs must run on the main thread" }
                lock.lock()
                try {
                    while (taskQ.isEmpty()) {
                        writer.await()
                    }
                    val task = taskQ.removeFirst()
                    log("Servicing work request for $task (queue: $taskQ)")
                    hasTask[task.ordinal] = false
                    readers[task.ordinal].signal()
                    task.job()
                    log("Completed work request for $task (queue: $taskQ)")
                } finally {
                    lock.unlock()
                }
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
                val data = genGuidance.fuzzState.snapshot()
                return Json.encodeToString(data)
            }

            /**
             * PATCH performs a partial update to EIs, leaving ones that weren't included in the request untouched.
             */
            override fun onPatch(reader: BufferedReader): String {
                val text = reader.readText()
                val arr = Json.decodeFromString<List<EiWithoutStackTrace>>(text)
                for (e in arr) {
                    genGuidance.fuzzState.update(e.ei, e.choice)
                }
                return "OK"
            }
        })
        server.createContext("/reset", object : ResponseHandler("reset") {
            /**
             * Nukes the fuzzing session and starts afresh.
             */
            override fun onPost(reader: BufferedReader): String {
                println("Clearing EI map and restarting...")
                genGuidance.fuzzState.clear()
                MainThreadTask.RERUN_GENERATOR.requestWork()
                return "OK"
            }
        })
        server.createContext("/history", object : ResponseHandler("history") {
            override fun onGet(): String = Json.encodeToString(genGuidance.fuzzState.history)
        })
        server.createContext("/save_input", SaveInputHandler())
        server.createContext("/load_input", LoadInputHandler())
        server.createContext("/save_session", SaveSessionHandler())
        server.createContext("/load_session", LoadSessionHandler())
        server.createContext("/generator", GenHandler())
        server.start()
        println("Server initialized at port " + server.address.port)
        while (true) {
            MainThreadTask.waitForJob()
        }
    }

    private fun init() {
        mainThread = Thread.currentThread()
        System.setProperty("jqf.traceGenerators", "true")
        // Needed for some jank call tracking
        dummy()
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
        println("Updated generator contents (map is of size ${genGuidance.fuzzState.mapSize})")
    }

    private fun getGenContents(): String {
        val genContents = genGuidance.fuzzState.genOutput
        return genContents.substring(0, genContents.length.coerceAtMost(1024))
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
        genGuidance.fuzzState.genOutput = jsGen.generate(random, genStatus)
        println(genGuidance.history.runResults.map { it.result })
        println("generator produced: " + getGenContents())
    }

    private class GenHandler : ResponseHandler("generator") {
        override fun onGet(): String {
            return getGenContents()
        }

        override fun onPost(reader: BufferedReader): String {
            // Need to yield back to main thread, which is running a loop with a monitor
            // that just runs the generator when notified
            MainThreadTask.RERUN_GENERATOR.requestWork()
            return getGenContents()
        }
    }

    val saveInputDir = File("savedInputs")
    init {
        assert(saveInputDir.mkdirs()) { "Unable to create saved input directory at $saveInputDir" }
    }

    private fun resolveInputFile(fileName: String): File = saveInputDir.toPath().resolve(fileName).toFile()

    private class SaveInputHandler : ResponseHandler("save_input") {
        @Serializable
        private data class SaveRequest(val fileName: String)

        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val saveRequest = Json.decodeFromString<SaveRequest>(text)
            val saveFile = resolveInputFile(saveRequest.fileName)
            println("Saving last run to ${saveFile.canonicalPath}")
            genGuidance.writeLastRun(saveFile)
            return "OK"
        }
    }

    private class LoadInputHandler : ResponseHandler("load_input") {
        @Serializable
        private data class LoadRequest(val fileName: String)

        /**
         * Returns a list of all saved inputs available for repro.
         */
        override fun onGet(): String {
            return Json.encodeToString<List<String>>(saveInputDir.list()!!.toList())
        }

        /**
         * Replaces the current values in the EI map with the values in the specified file.
         */
        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val loadRequest = Json.decodeFromString<LoadRequest>(text)
            // TODO handle error case i guess
            val loadFile = resolveInputFile(loadRequest.fileName)
            println("Loading run from ${loadFile.canonicalPath}")
            genGuidance.reproWithFile(loadFile).use {
                MainThreadTask.LOAD_FROM_FILE.requestWork()
            }
            return "OK"
        }
    }

    private val saveSessionDir = File("savedSessions")
    init {
        assert(saveSessionDir.mkdirs()) { "Unable to create saved session directory at $saveSessionDir" }
    }

    private fun resolveSessionFile(fileName: String): File = saveSessionDir.toPath().resolve(fileName).toFile()

    private class SaveSessionHandler : ResponseHandler("save_session") {
        @Serializable
        private data class SaveRequest(val fileName: String)

        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val saveRequest = Json.decodeFromString<SaveRequest>(text)
            val saveFile = resolveSessionFile(saveRequest.fileName)
            println("Saving session history to ${saveFile.canonicalPath}")
            genGuidance.writeSessionHistory(saveFile)
            return "OK"
        }
    }

    private class LoadSessionHandler : ResponseHandler("load_session") {
        @Serializable
        private data class LoadRequest(val fileName: String)

        /**
         * Returns a list of all saved sessions available to load.
         */
        override fun onGet(): String {
            return Json.encodeToString<List<String>>(saveSessionDir.list()!!.toList())
        }

        /**
         * Replaces the current fuzzing state with the history stored in the given file and reruns the generator
         * once the state is restored.
         *
         * Nukes the existing state before the load occurs.
         */
        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val loadRequest = Json.decodeFromString<LoadRequest>(text)
            val loadFile = resolveSessionFile(loadRequest.fileName)
            println("Loading session history from ${loadFile.canonicalPath}")
            genGuidance.loadSessionHistory(loadFile)
            MainThreadTask.RERUN_GENERATOR.requestWork()
            return Json.encodeToString(genGuidance.fuzzState.history)
//            return "OK"
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