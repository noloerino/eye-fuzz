import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.function.Consumer;

import edu.berkeley.cs.jqf.examples.js.JavaScriptCodeGenerator;
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex;
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndexingState;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.NonTrackingGenerationStatus;
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;

/**
 * Manages the HTTP connection to dispatch the generator and fuzzer as needed.
 *
 * Since we only have one server at a time, everything is static. Yay.
 */
public class Server {
    private static LinkedHashMap<ExecutionIndex, Integer> eiMap = new LinkedHashMap<>();
    private static String genContents = "";
    private static Random rng = new Random();
    private static final EiManualMutateGuidance genGuidance = new EiManualMutateGuidance();

    // CHANGE THIS FOR OTHER TARGETS
    private static final JavaScriptCodeGenerator jsGen = new JavaScriptCodeGenerator();

    private static void init() {
        System.setProperty("jqf.traceGenerators", "true");
        SingleSnoop.setCallbackGenerator(genGuidance::generateCallBack);
        String className = jsGen.getClass().getName();
        SingleSnoop.startSnooping( className + "#" + "generate");
        runGenerator();
    }

    /**
     * Reruns the generator to update the generator string.
     * See Zest fuzzing loop.
     * https://github.com/rohanpadhye/JQF/blob/0152e82d4eb414b06438dec3ef0322135318291a/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java#L159
     */
    private static void runGenerator() {
        StreamBackedRandom randomFile = new StreamBackedRandom(genGuidance.getInput(), Long.BYTES);
        SourceOfRandomness random = new FastSourceOfRandomness(randomFile);
        GenerationStatus genStatus = new NonTrackingGenerationStatus(random);
        genContents = jsGen.generate(random, genStatus);
    }

    /**
     * Analogous to a ExecutionIndexGuidance, but is backed by the above eiMap.
     * Needs to record coverage for EI to work.
     */
    private static class EiManualMutateGuidance implements Guidance {
        private Thread appThread = null; // Ensures only one thread
        private TraceEvent lastEvent = null;
        private ExecutionIndexingState eiState = new ExecutionIndexingState();

        @Override
        public InputStream getInput() throws IllegalStateException, GuidanceException {
            eiState = new ExecutionIndexingState();
            return new InputStream() {
                @Override
                public int read() {
                    if (lastEvent == null) {
                        throw new GuidanceException("Could not compute execution index; no instrumentation?");
                    }
                    // Get the execution index of the last event
                    ExecutionIndex executionIndex = eiState.getExecutionIndex(lastEvent);
                    // Attempt to get a value from the map, or else generate a random value
                    if (!eiMap.containsKey(executionIndex)) {
                        eiMap.put(executionIndex, rng.nextInt(256));
                    }
                    return eiMap.get(executionIndex);
                }
            };
        }

        @Override
        public boolean hasInput() {
            return true;
        }

        @Override
        public void handleResult(Result result, Throwable throwable) throws GuidanceException { }

        @Override
        public Consumer<TraceEvent> generateCallBack(Thread thread) {
            if (appThread != null) {
                throw new IllegalStateException("Guidance must run on main thread");
            }
            appThread = thread;
            String entryPoint = SingleSnoop.entryPoints.get(thread);
            if (entryPoint == null) {
                throw new IllegalStateException("Guidance must be able to determine entry point");
            }
            return e -> {
                lastEvent = e;
                e.applyVisitor(eiState);
            };
        }
    }

    public static void main(String[] args) throws IOException {
        init();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8000), 0);
        server.createContext("/generator", httpExchange -> {
            System.out.println("Hit /generator");
            String method = httpExchange.getRequestMethod();
            switch (method) {
                case "GET":
                    httpExchange.sendResponseHeaders(501, genContents.length());
                    try (OutputStream out = httpExchange.getResponseBody()) {
                        out.write(genContents.getBytes());
                    }
                    break;
                case "POST":
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(httpExchange.getRequestBody()))) {
                        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                            System.out.println("BEGIN POST BODY");
                            System.out.println(line);
                            System.out.println("END POST BODY");
                        }
                    }
                    String response = "OK";
                    httpExchange.sendResponseHeaders(200, response.length());
                    try (OutputStream out = httpExchange.getResponseBody()) {
                        out.write(response.getBytes());
                    }
                    break;
                default:
                    httpExchange.sendResponseHeaders(501, 0);
            }
            httpExchange.close();
        });
        server.createContext("/coverage", httpExchange -> {
            System.out.println("Hit /coverage");
            httpExchange.sendResponseHeaders(501, 0);
            httpExchange.close();
        });
        server.start();
        System.out.println("Server initialized at port " + server.getAddress().getPort());
    }
}
