import com.pholser.junit.quickcheck.generator.GenerationStatus
import com.pholser.junit.quickcheck.generator.Generator
import com.sun.net.httpserver.HttpServer
import edu.berkeley.cs.jqf.fuzz.guidance.Result
import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.NonTrackingGenerationStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.tools.ExecFileLoader
import org.jacoco.report.csv.CSVFormatter
import java.io.*
import java.lang.reflect.ParameterizedType
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.locks.ReentrantLock


/**
 * Since the main thread is the only thread that can run the generator, threads of the HTTP server will have to
 * yield to the main thread by way of monitors in order to rerun the generator.
 *
 * The system is assumed to have a single writer (the main thread), but possibly multiple readers.
 */
private enum class MainThreadTask {
    RERUN_GENERATOR {
        override fun job(server: Server<*>) {
            server.runGenerator()
        }
    },

    /**
     * This job must be requested within the context provided by genGuidance.reproWithFile.
     */
    LOAD_FROM_FILE {
        override fun job(server: Server<*>) {
            require(server.genGuidance.isInReproMode) { "Repro job was requested outside guidance repro mode"}
            server.runGenerator()
        }
    },

    RUN_TEST_CASE {
        override fun job(server: Server<*>) {
            server.runTestCase()
        }
    },

    TURN_OFF {
        override fun job(server: Server<*>) {
            server.isRunning = false
        }
    }
    ;

    abstract fun job(server: Server<*>)

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
        // Therefore, we must maintain a task queue rather than a single state variable
        lock.lock()
        try {
            // The condition readers[i] is associated with the presence of task hasTask[i]
            // We cannot rely on the queue being empty in case another work request was sneaked in
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
         * Must be called on the writer (main) thread if not in testing mode.
         */
        fun waitForJob(server: Server<*>) {
            require(server.isUnderUnitTest || Thread.currentThread() == server.mainThread) {
                "Jobs must run on the main thread"
            }
            lock.lock()
            try {
                while (taskQ.isEmpty()) {
                    writer.await()
                }
                val task = taskQ.removeFirst()
                log("Servicing work request for $task (queue: $taskQ)")
                hasTask[task.ordinal] = false
                readers[task.ordinal].signal()
                task.job(server)
                log("Completed work request for $task (queue: $taskQ)")
            } finally {
                lock.unlock()
            }
        }
    }
}

/**
 * Manages the HTTP connection to dispatch the generator and fuzzer as needed. The type parameter identifies
 * the generator and its output type.
 *
 * Since we only have one server at a time, everything is static. Yay.
 */
class Server<T>(private val gen: Generator<T>,
                testClassName: String,
                private val testMethod: String,
                private val covClassNames: List<String>,
                private val genOutputSerializer: (T?) -> String = { v -> v.toString() }) {
    // WARNING: IF EVER THE SERVER GOES BACK TO USING ZEST ExecutionIndex,
    // PASSING toString AS A DEFAULT ARGUMENT WILL BREAK EI TRACKING FOR SOME DUMB REASON
    // FIX: CREATE A SECOND CONSTRUCTOR THAT PASSES A DEFAULT toString

    init {
        covClassNames.forEach { className ->
            require(this::class.java.getResource("/${className.replace('.', '/')}.class") != null)
                { "Couldn't find coverage class $className" }
        }
    }

    /**
     * Determines whether the server is being run in unit test mode.
     * In unit test mode, the behavior of response handlers is exposed, but the actual HTTP server isn't activated.
     * This should be set programmatically before the start() method is called.
     */
    var isUnderUnitTest = false

    internal var isRunning = false

    private val testClass = Class.forName(testClassName)
    val mainThread: Thread = Thread.currentThread()

    // Need to compute type that generator produces at runtime
    @Suppress("UNCHECKED_CAST")
    private val genOutputClass: Class<T> =
            (Class.forName(gen.javaClass.name).genericSuperclass as ParameterizedType)
                    .actualTypeArguments[0] as Class<T>

    private val rng = Random()
    internal val genGuidance = EiManualMutateGuidance<T>(rng, genOutputSerializer)

    // Does not bind to port immediately
    private val server = HttpServer.create()

    /**
     * A map of response handlers. Used to allow test cases to easily access the server without having to
     * actually perform HTTP requests.
     * These must all be initialized before start() is called.
     */
    val responseHandlers: Map<String, ResponseHandler> get() = _responseHandlers
    // Backing field needed to hide mutability from API
    private val _responseHandlers: MutableMap<String, ResponseHandler> = mutableMapOf()

    private fun addHandlers(vararg handlers: ResponseHandler) {
        handlers.forEach {
            _responseHandlers[it.name] = it
            server.createContext("/${it.name}", it)
        }
    }

    init {
        addHandlers(
                object : ResponseHandler("ei") {
                    /**
                     * GET returns a list of all choices/locations in the map.
                     */
                    override fun onGet(): String {
                        val data = genGuidance.fuzzState.snapshot()
                        return Json.encodeToString(data)
                    }

                    /**
                     * PATCH performs a partial update to the choice map, leaving choices ones that weren't included in
                     * the request untouched.
                     */
                    override fun onPatch(reader: BufferedReader): String {
                        val text = reader.readText()
                        val arr = Json.decodeFromString<List<LocFromPatch>>(text)
                        for (e in arr) {
                            genGuidance.fuzzState.update(e.index, e.choice)
                        }
                        return "OK"
                    }
                },
                object : ResponseHandler("reset") {
                    /**
                     * Nukes the fuzzing session and starts afresh.
                     */
                    override fun onPost(reader: BufferedReader): String {
                        println("Clearing EI map and restarting...")
                        genGuidance.fuzzState.clear()
                        MainThreadTask.RERUN_GENERATOR.requestWork()
                        return "OK"
                    }
                },
                object : ResponseHandler("history") {
                    override fun onGet(): String = Json.encodeToString(genGuidance.fuzzState.history)
                },
                SaveInputHandler(),
                LoadInputHandler(),
                SaveSessionHandler(),
                LoadSessionHandler(),
                // Runs the generator and/or gets the generator result
                object : ResponseHandler("generator") {
                    override fun onGet(): String = getGenContents()

                    override fun onPost(reader: BufferedReader): String {
                        // Need to yield back to main thread, which is running a loop with a monitor
                        // that just runs the generator when notified
                        MainThreadTask.RERUN_GENERATOR.requestWork()
                        return getGenContents()
                    }
                },
                // Runs a test case, or gets test coverage
                object : ResponseHandler("run_test") {
                    override fun onGet(): String = Json.encodeToString(getTestCaseCov())

                    override fun onPost(reader: BufferedReader): String {
                        genGuidance.collectTestCov().use {
                            MainThreadTask.RUN_TEST_CASE.requestWork()
                        }
                        return Json.encodeToString(getTestCaseCov())
                    }
                },
        )
    }

    fun start() {
        init()
        if (!isUnderUnitTest) {
            server.bind(InetSocketAddress("localhost", 8000), 0)
            server.start()
            println("Server initialized at port " + server.address.port)
        }
        try {
            while (isRunning) {
                MainThreadTask.waitForJob(this)
            }
        } finally {
            println("Server exiting")
            if (!isUnderUnitTest) {
                server.stop(1)
            }
        }
    }

    private fun init() {
        this.runGenerator()
        this.runTestCase()
        isRunning = true
    }

    fun stop() {
        MainThreadTask.TURN_OFF.requestWork()
    }

    /**
     * Runs the generator.
     */
    internal fun runGenerator() {
        genGuidance.reset()
        println("Starting generator run")
        this.guidanceStub()
        println("Updated generator contents (map is of size ${genGuidance.fuzzState.mapSize})")
    }

    /**
     * Shared state for CSV of Jacoco coverage results. Filled by `runTestCase`, consumed by `getTestCaseCov`.
     */
    private var testResultStream: ByteArrayOutputStream = ByteArrayOutputStream()

    private var lastTestResult: Result = Result.INVALID

    /**
     * Runs a test case and obtains coverage for it through Jacoco.
     */
    fun runTestCase() {
        // Reinitialize stream
        testResultStream = ByteArrayOutputStream()
        fun getTargetClass(targetName: String) = this::class.java
                .getResourceAsStream("/${targetName.replace('.', '/')}.class")
        println("Starting Jacoco test case run")
        val testWrapper = TestWrapper(genGuidance.fuzzState.genOutput, testClass, testMethod, genOutputClass)
        testWrapper.run()
        lastTestResult = testWrapper.lastTestResult
        println("test result: $lastTestResult")
        // https://github.com/rohanpadhye/JQF/blob/master/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/repro/ReproGuidance.java
        val agent = Class.forName("org.jacoco.agent.rt.RT")
                .getMethod("getAgent")
                .invoke(null)
        val execData: ByteArray = agent.javaClass.getMethod("getExecutionData", Boolean::class.java)
                .invoke(agent, false) as ByteArray
        val loader = ExecFileLoader()
        loader.load(ByteArrayInputStream(execData))
        val executionData = loader.executionDataStore
        val coverageBuilder = CoverageBuilder()
        val analyzer = Analyzer(executionData, coverageBuilder)
        getTargetClass(testClass.name).use {
            analyzer.analyzeClass(it, testClass.name)
        }
        covClassNames.forEach { className ->
            getTargetClass(className).use {
                analyzer.analyzeClass(it, className)
            }
        }

        // https://www.jacoco.org/jacoco/trunk/doc/examples/java/ReportGenerator.java
        val csvFormatter = CSVFormatter()
        val visitor = csvFormatter.createVisitor(testResultStream)
        visitor.visitBundle(coverageBuilder.getBundle("eye-fuzz"), null)
        visitor.visitEnd()

        // Dump coverage info
//        fun printCounter(unit: String, counter: ICounter) {
//            val missed = counter.missedCount
//            val total = counter.totalCount
//            println("> $missed of $total $unit missed")
//        }
//        println("no match: ${coverageBuilder.noMatchClasses}")
//        for (cc in coverageBuilder.classes) {
//            println("Coverage of class ${cc.name}")
//            printCounter("instructions", cc.instructionCounter)
//            printCounter("branches", cc.branchCounter)
//            printCounter("lines", cc.lineCounter)
//            printCounter("methods", cc.methodCounter)
//            printCounter("complexity", cc.complexityCounter)
//            for (i in cc.firstLine..cc.lastLine) {
//                val statStr = when (cc.getLine(i).status) {
//                    ICounter.NOT_COVERED -> "red"
//                    ICounter.PARTLY_COVERED -> "yellow"
//                    ICounter.FULLY_COVERED -> "green"
//                    else -> "???"
//                }
//                println("Line $i: $statStr")
//            }
//        }
        println("Finished test case run")
    }

    private fun getGenContents(): String {
        val genContents = genGuidance.fuzzState.genOutput
        val serialized = genOutputSerializer(genContents)
        return serialized.substring(0, serialized.length.coerceAtMost(1024))
    }

    private fun getTestCaseCov(): TestCovResult {
        return TestCovResult(lastTestResult, String(testResultStream.toByteArray()))
    }

    /**
     * reruns the generator
     */
    private fun guidanceStub() {
        val randomFile = StreamBackedRandom(genGuidance.input, java.lang.Long.BYTES)
        val random = AnnotatingRandomSource(randomFile)
        val genStatus: GenerationStatus = NonTrackingGenerationStatus(random)
        genGuidance.annotatingRandomSource = random
        genGuidance.fuzzState.genOutput = gen.generate(random, genStatus)
        println(genGuidance.history.runResults.map { it.serializedResult })
        println("generator produced: " + getGenContents())
    }

    val saveInputDir = File("savedInputs")
    init {
        assert(saveInputDir.isDirectory || saveInputDir.mkdirs()) {
            "Unable to create saved input directory at ${saveInputDir.canonicalPath}"
        }
    }

    private fun resolveInputFile(fileName: String): File = saveInputDir.toPath().resolve(fileName).toFile()

    @Serializable
    data class SaveLoadRequest(val fileName: String)

    private inner class SaveInputHandler : ResponseHandler("save_input") {
        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val saveRequest = Json.decodeFromString<SaveLoadRequest>(text)
            val saveFile = resolveInputFile(saveRequest.fileName)
            println("Saving last run to ${saveFile.canonicalPath}")
            genGuidance.writeLastRun(saveFile)
            return "OK"
        }
    }

    private inner class LoadInputHandler : ResponseHandler("load_input") {
        /**
         * Returns a list of all saved inputs available for repro.
         */
        override fun onGet(): String {
            return Json.encodeToString<List<String>>(saveInputDir.list()!!.toList())
        }

        /**
         * Replaces the current values in the choice map with the values in the specified file.
         */
        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val loadRequest = Json.decodeFromString<SaveLoadRequest>(text)
            // TODO handle error case i guess
            val loadFile = resolveInputFile(loadRequest.fileName)
            println("Loading run from ${loadFile.canonicalPath}")
            genGuidance.reproWithFile(loadFile).use {
                MainThreadTask.LOAD_FROM_FILE.requestWork()
            }
            return "OK"
        }
    }

    val saveSessionDir = File("savedSessions")
    init {
        assert(saveSessionDir.isDirectory || saveSessionDir.mkdirs()) {
            "Unable to create saved session directory at ${saveSessionDir.canonicalPath}"
        }
    }

    private fun resolveSessionFile(fileName: String): File = saveSessionDir.toPath().resolve(fileName).toFile()

    private inner class SaveSessionHandler : ResponseHandler("save_session") {
        override fun onPost(reader: BufferedReader): String {
            val text = reader.readText()
            val saveRequest = Json.decodeFromString<SaveLoadRequest>(text)
            val saveFile = resolveSessionFile(saveRequest.fileName)
            println("Saving session history to ${saveFile.canonicalPath}")
            genGuidance.writeSessionHistory(saveFile)
            return "OK"
        }
    }

    private inner class LoadSessionHandler : ResponseHandler("load_session") {
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
            val loadRequest = Json.decodeFromString<SaveLoadRequest>(text)
            val loadFile = resolveSessionFile(loadRequest.fileName)
            println("Loading session history from ${loadFile.canonicalPath}")
            genGuidance.loadSessionHistory(loadFile)
            MainThreadTask.RERUN_GENERATOR.requestWork()
            return Json.encodeToString(genGuidance.fuzzState.history)
        }
    }

    companion object {
        const val GUIDANCE_STUB_METHOD_NAME = "guidanceStub"
    }
}