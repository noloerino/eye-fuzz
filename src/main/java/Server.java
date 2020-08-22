import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

import com.sun.net.httpserver.HttpServer;
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex;
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndexingState;
import edu.berkeley.cs.jqf.fuzz.guidance.Guidance;
import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.junit.GuidedFuzzing;
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

    public static void main(String[] args) throws IOException {
        init();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8000), 0);
        server.createContext("/ei", new ResponseHandler("ei") {
            @Override
            public String onGet() {
                // For ease of parsing, we send the random byte first followed by a space; everything after the space
                // is the stack trace of the EI
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<ExecutionIndex, Integer> entry : eiMap.entrySet()) {
                    sb.append(entry.getValue());
                    sb.append(" ");
                    ExecutionIndex ei = entry.getKey();
                    sb.append(ei.toString());
                    sb.append("\n");
                }
                return sb.toString();
            }

            @Override
            public String onPost(BufferedReader reader) throws IOException {
                // PUT expects parameters in a similar fashion, i.e. each line is int, space, EI array
                LinkedHashMap<ExecutionIndex, Integer> newEiMap = new LinkedHashMap<>();
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String[] chunks = line.replace(",", "")
                            .replace("[", "")
                            .replace("]", "")
                            .split(" ");
                    int data = Integer.parseInt(chunks[0]);
                    int[] eiArr = new int[chunks.length - 1];
                    for (int i = 1; i < chunks.length; i++) {
                        eiArr[i - 1] = Integer.parseInt(chunks[i].trim());
                    }
                    newEiMap.put(new ExecutionIndex(eiArr), data);
                }
                eiMap = newEiMap;
                return "OK";
            }
        });
        server.createContext("/generator",
                new ResponseHandler("generator") {
                    @Override
                    public String onGet() {
                        return genContents;
                    }

                    @Override
                    public String onPost(BufferedReader reader) throws IOException {
                        // Rerun the generator and return the contents
                        runGenerator();
                        return genContents;
                    }
                });
        server.createContext("/coverage",
                new ResponseHandler("coverage") {
                    @Override
                    public String onGet() {
                        return "";
                    }
                }
        );
        server.start();
        System.out.println("Server initialized at port " + server.getAddress().getPort());
    }

    private static void init() {
        System.setProperty("jqf.traceGenerators", "true");
        runGenerator();
    }

    /**
     * Reruns the generator to update the generator string.
     * See Zest fuzzing loop.
     * https://github.com/rohanpadhye/JQF/blob/0152e82d4eb414b06438dec3ef0322135318291a/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java#L159
     */
    private static void runGenerator() {
        genGuidance.reset();
        Guidance g = genGuidance;
        // Sanity check for instrumentation
//        try {
//            g = new ExecutionIndexingGuidance(
//                    "testWithGenerator", Duration.ofSeconds(5), new File("target"));
//        } catch (IOException ignored) {
//            g = null;
//        }
        GuidedFuzzing.run(
                DummyTest.class,
                "testWithGenerator",
                g,
                null
        );
        genContents = DummyTest.generated;
        System.out.println("generator produced: " + genContents);
    }

    /**
     * Analogous to a ExecutionIndexGuidance, but is backed by the above eiMap.
     * Needs to record coverage for EI to work.
     * Run only once.
     */
    private static class EiManualMutateGuidance implements Guidance {
        private Thread appThread = null; // Ensures only one thread
        private TraceEvent lastEvent = null;
        private ExecutionIndexingState eiState = new ExecutionIndexingState();
        private boolean hasRun = false;

        public void reset() {
            hasRun = false;
            eiState = new ExecutionIndexingState();
        }

        @Override
        public InputStream getInput() throws IllegalStateException, GuidanceException {
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
            return !hasRun;
        }

        @Override
        public void handleResult(Result result, Throwable throwable) throws GuidanceException {
            hasRun = true;
        }

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
            return this::handleEvent;
        }

        private void handleEvent(TraceEvent e) {
            lastEvent = e;
            e.applyVisitor(eiState);
        }
    }

    private static abstract class ResponseHandler implements HttpHandler  {
        private String name;

        public ResponseHandler(String name) {
            this.name = name;
        }

        @Override
        public final void handle(HttpExchange httpExchange) throws IOException {
            System.out.println("Hit /" + name);
            String method = httpExchange.getRequestMethod();
            Headers headers = httpExchange.getResponseHeaders();
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods","GET,POST");
            String response = "";
            switch (method) {
                case "GET":
                    response = onGet();
                    httpExchange.sendResponseHeaders(200, response.length());
                    break;
                case "POST":
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(httpExchange.getRequestBody()))) {
                        response = onPost(reader);
                    }
                    httpExchange.sendResponseHeaders(200, response.length());
                    break;
                default:
                    httpExchange.sendResponseHeaders(501, 0);
            }
            try (OutputStream out = httpExchange.getResponseBody()) {
                out.write(response.getBytes());
            }
            httpExchange.close();
        }

        /**
         * @return the string to be written as a response
         */
        public String onGet() {
            return "";
        }

        /**
         * @param reader the request body
         * @return the string to be written as a response
         */
        public String onPost(BufferedReader reader) throws IOException {
            return "";
        }
    }
}
