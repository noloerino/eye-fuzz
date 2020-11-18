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
import org.jacoco.core.analysis.ICounter
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.LoggerRuntime
import org.jacoco.core.runtime.RuntimeData
import java.io.BufferedReader
import java.io.File
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
        // Therefore, we must be maintain a task queue rather than a single state variable
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
         * Must be called on the writer thread.
         *
         * @return the requested task item
         */
        fun waitForJob(server: Server<*>) {
            require(Thread.currentThread() == server.mainThread) { "Jobs must run on the main thread" }
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
                private val genOutputSerializer: (T) -> String) {
    // WARNING: REMOVING THIS CONSTRUCTOR AND PASSING toString AS A DEFAULT ARGUMENT WILL BREAK EI TRACKING
    // FOR SOME DUMB REASON
    constructor(gen: Generator<T>, testClassName: String, testMethod: String)
        : this(gen, testClassName, testMethod, { v -> v.toString() })

    private val testClass = Class.forName(testClassName)
    val mainThread: Thread = Thread.currentThread()

    /** Tracks the random value stored at a choice, as well as the last line of the stack trace  */
    private val rng = Random()
    internal val genGuidance = EiManualMutateGuidance(rng)

    /** The result of the last test case run */
    var testResult: org.junit.runner.Result? = null

    fun start() {
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
                val arr = Json.decodeFromString<List<LocFromPatch>>(text)
                for (e in arr) {
                    genGuidance.fuzzState.update(e.index, e.choice)
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
        server.createContext("/run_test", RunTestHandler())
        server.start()
        println("Server initialized at port " + server.address.port)
        while (true) {
            MainThreadTask.waitForJob(this)
        }
    }

    private fun init() {
        System.setProperty("jqf.traceGenerators", "true")
        this.runGenerator()
//        this.runTestCase()
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
     * A class loader that loads classes from in-memory data.
     * Copied from https://www.jacoco.org/jacoco/trunk/doc/examples/java/CoreTutorial.java
     */
    class MemoryClassLoader : ClassLoader() {
        private val definitions: MutableMap<String, ByteArray> = HashMap()

        fun addDefinition(name: String, bytes: ByteArray) {
            definitions[name] = bytes
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val bytes = definitions[name]
            return if (bytes != null) {
                defineClass(name, bytes, 0, bytes.size)
            } else super.loadClass(name, resolve)
        }
    }

    /**
     * Runs a test case and obtains coverage for it through Jacoco.
     */
    fun runTestCase() {
        fun getTargetClass(targetName: String) = this::class.java
                .getResourceAsStream("/${targetName.replace('.', '/')}.class")
        println("Starting Jacoco test case run")
        // https://www.jacoco.org/jacoco/trunk/doc/examples/java/CoreTutorial.java
        // Unlike the example, we need to target a wrapper class for runnable
        val targetName = TestWrapper::class.java.name
        val runtime = LoggerRuntime()
        // Create modified class with instrumentation
        val instr = Instrumenter(runtime)
        val instrumented: ByteArray = getTargetClass(targetName).use {
            instr.instrument(it, targetName)
        }
        // Run instrumented class
        val data = RuntimeData()
        val memoryClassLoader = MemoryClassLoader()
        memoryClassLoader.addDefinition(targetName, instrumented)
        val targetClass: Class<*> = memoryClassLoader.loadClass(targetName)
        // Execute class
        val targetInstance = targetClass.newInstance() as Runnable
        fun setTargetField(fieldName: String, value: Any) {
            val field = targetInstance.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            field.set(targetInstance, value)
        }
//        println("declared fields: ${targetInstance.javaClass.declaredFields.map { it.name }}")
        setTargetField("genOutput", genGuidance.fuzzState.genOutput)
        setTargetField("testClass", testClass)
        setTargetField("testMethod", testMethod)
        targetInstance.run()
        lastTestResult = targetInstance.javaClass.getField("lastTestResult").get(targetInstance) as Result
        println("test result: $lastTestResult")
        // Collect data and end runtime
        val executionData = ExecutionDataStore()
        val sessionInfos = SessionInfoStore()
        data.collect(executionData, sessionInfos, false)
        println("exec data: ${executionData.contents.map { it.name }}")
        runtime.shutdown()
        // Get coverage on test target, not runnable wrapper
        val coverageBuilder = CoverageBuilder()
        val analyzer = Analyzer(executionData, coverageBuilder)
        getTargetClass(targetName).use {
            analyzer.analyzeClass(it, targetName)
        }
        getTargetClass(testClass.name).use {
            analyzer.analyzeClass(it, testClass.name)
        }
        // TODO make class name array? configurable
        val compName = com.google.javascript.jscomp.Compiler::class.java.name
        getTargetClass(compName).use {
            analyzer.analyzeClass(it, compName)
        }
        // Dump coverage info
        fun printCounter(unit: String, counter: ICounter) {
            val missed = counter.missedCount
            val total = counter.totalCount
            println("> $missed of $total $unit missed")
        }
        println("no match: ${coverageBuilder.noMatchClasses}")
        for (cc in coverageBuilder.classes) {
            println("Coverage of class ${cc.name}")
            printCounter("instructions", cc.instructionCounter)
            printCounter("branches", cc.branchCounter)
            printCounter("lines", cc.lineCounter)
            printCounter("methods", cc.methodCounter)
            printCounter("complexity", cc.complexityCounter)
            /*
            for (i in cc.firstLine..cc.lastLine) {
                val statStr = when (cc.getLine(i).status) {
                    ICounter.NOT_COVERED -> "red"
                    ICounter.PARTLY_COVERED -> "yellow"
                    ICounter.FULLY_COVERED -> "green"
                    else -> "???"
                }
                println("Line $i: $statStr")
            }
            */
        }
        println("Finished test case run")
    }

    private fun getGenContents(): String {
        val genContents = genGuidance.fuzzState.genOutput
        return genContents.substring(0, genContents.length.coerceAtMost(1024))
    }

    private fun getTestCaseCov(): TestCovResult {
        return TestCovResult(
                lastTestResult,
                // Need to filter to ensure size not too large
                genGuidance.lastRunTestCov.toSet()
                        .map { "TODO" } // TODO
                        .filter { it.contains(testClass.name) }
        )
    }

    /**
     * reruns the generator
     */
    private fun guidanceStub() {
        val randomFile = StreamBackedRandom(genGuidance.input, java.lang.Long.BYTES)
        val random = AnnotatingRandomSource(randomFile)
        val genStatus: GenerationStatus = NonTrackingGenerationStatus(random)
        genGuidance.annotatingRandomSource = random
        genGuidance.fuzzState.genOutput = genOutputSerializer(gen.generate(random, genStatus))
        println(genGuidance.history.runResults.map { it.serializedResult })
        println("generator produced: " + getGenContents())
    }

    private var lastTestResult: Result = Result.SUCCESS

    // ===================== TEST CASE API STUFF =====================
    private inner class RunTestHandler : ResponseHandler("run_test") {
        override fun onGet(): String {
            return Json.encodeToString(getTestCaseCov())
        }

        override fun onPost(reader: BufferedReader): String {
            genGuidance.collectTestCov().use {
                MainThreadTask.RUN_TEST_CASE.requestWork()
            }
            return Json.encodeToString(getTestCaseCov())
        }
    }

    // ===================== GENERATOR API STUFF =====================

    private inner class GenHandler : ResponseHandler("generator") {
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

    private val saveInputDir = File("savedInputs")
    init {
        assert(saveInputDir.mkdirs()) { "Unable to create saved input directory at $saveInputDir" }
    }

    private fun resolveInputFile(fileName: String): File = saveInputDir.toPath().resolve(fileName).toFile()

    @Serializable
    private data class SaveLoadRequest(val fileName: String)

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
         * Replaces the current values in the EI map with the values in the specified file.
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

    private val saveSessionDir = File("savedSessions")
    init {
        assert(saveSessionDir.mkdirs()) { "Unable to create saved session directory at $saveSessionDir" }
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
//            return "OK"
        }
    }
}