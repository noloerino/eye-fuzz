import com.pholser.junit.quickcheck.generator.GenerationStatus
import com.pholser.junit.quickcheck.generator.Generator
import com.pholser.junit.quickcheck.random.SourceOfRandomness
import com.sun.net.httpserver.HttpServer
import edu.berkeley.cs.jqf.fuzz.guidance.Result
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException
import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom
import edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException
import edu.berkeley.cs.jqf.fuzz.junit.TrialRunner
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.NonTrackingGenerationStatus
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop
import edu.berkeley.cs.jqf.instrument.tracing.TraceLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AssumptionViolatedException
import org.junit.runners.model.FrameworkMethod
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

    /** Tracks the random value stored at an EI, as well as the last line of the stack trace  */
    private val rng = Random()
    internal val genGuidance = EiManualMutateGuidance(rng, mainThread)

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
    }

    /**
     * Serves as an entry point to the tracking of the EI call stack.
     * Returns should probably stop tracking at generatorStub to avoid an oob exception.
     */
    internal fun runGenerator() {
        TraceLogger.get().remove()
        SingleSnoop.setCallbackGenerator(genGuidance::generateCallBack)
        SingleSnoop.startSnooping(GUIDANCE_STUB_FULL_NAME)
        genGuidance.reset()
        println("Starting generator run (entp: ${SingleSnoop.entryPoints})")
        this.guidanceStub(true)
        println("Updated generator contents (map is of size ${genGuidance.fuzzState.mapSize})")
    }

    /**
     * Runs a test case and obtains coverage for it.
     *
     * See the JQF class, and the way it interacts with the Fuzz annotation.
     */
    fun runTestCase() {
        TraceLogger.get().remove()
        SingleSnoop.setCallbackGenerator(genGuidance::generateCallBack)
        SingleSnoop.startSnooping(GUIDANCE_STUB_FULL_NAME)
        println("Starting test case run (entp: ${SingleSnoop.entryPoints})")
        this.guidanceStub(false)
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
                    .map { EiManualMutateGuidance.eventToString(it) }
                    .filter { it.contains(testClass.name) }
        )
    }

    /**
     * @param runGen if true, then reruns the generator; if false, then collects coverage data
     *
     * This weird coalescing into a single method is necessary to ensure that both methods can use the same entry point,
     * as SingleSnoop seems to be unhappy otherwise.
     * I verified this behavior by invoking runTest and runGenerator in sequence upon initialization of the server,
     * and found that coverage would be collected only for the first of the two.
     *
     * TODO refactor argument to account for boolean blindness
     */
    private fun guidanceStub(runGen: Boolean) {
        if (runGen) {
            val randomFile = StreamBackedRandom(genGuidance.input, java.lang.Long.BYTES)
            val random = AnnotatingRandomSource(randomFile)
            val genStatus: GenerationStatus = NonTrackingGenerationStatus(random)
            genGuidance.annotatingRandomSource = random
            genGuidance.fuzzState.genOutput = genOutputSerializer(gen.generate(random, genStatus))
            println(genGuidance.history.runResults.map { it.serializedResult })
            println("generator produced: " + getGenContents())
        } else {
            // TODO generalize by saving current obj rather than serialized string
            val method = FrameworkMethod(testClass.getMethod(testMethod, String::class.java))
            val testRunner = TrialRunner(testClass, method, arrayOf(genGuidance.fuzzState.genOutput))
            // Handle exceptions (see FuzzStatement)
            // https://github.com/rohanpadhye/JQF/blob/master/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java
            val expectedExceptions = method.method.exceptionTypes
            val result: Result = try {
                testRunner.run()
                Result.SUCCESS
            } catch (e: AssumptionViolatedException) {
                Result.INVALID
            } catch (e: TimeoutException) {
                Result.TIMEOUT
            } catch (e: GuidanceException) {
                throw e // Propagate error so we can quit
            } catch (t: Throwable) {
                if (expectedExceptions.any { it.isAssignableFrom(t::class.java) }) {
                    Result.SUCCESS
                } else {
                    Result.FAILURE
                }
            }
            lastTestResult = result
        }
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

    companion object {
        const val GUIDANCE_STUB_METHOD = "guidanceStub"
        val GUIDANCE_STUB_FULL_NAME = "${Server::class.java.name}#$GUIDANCE_STUB_METHOD"
    }
}