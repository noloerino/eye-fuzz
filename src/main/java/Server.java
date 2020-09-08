import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.util.*;

import com.sun.net.httpserver.HttpServer;
import edu.berkeley.cs.jqf.fuzz.ei.ExecutionIndex;
import edu.berkeley.cs.jqf.fuzz.guidance.StreamBackedRandom;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.FastSourceOfRandomness;
import edu.berkeley.cs.jqf.fuzz.junit.quickcheck.NonTrackingGenerationStatus;
import edu.berkeley.cs.jqf.instrument.tracing.SingleSnoop;
import edu.berkeley.cs.jqf.instrument.tracing.events.BranchEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.CallEvent;
import edu.berkeley.cs.jqf.instrument.tracing.events.TraceEvent;

/**
 * Manages the HTTP connection to dispatch the generator and fuzzer as needed.
 *
 * Since we only have one server at a time, everything is static. Yay.
 */
public class Server {
    /** Tracks the random value stored at an EI, as well as the last line of the stack trace */
    private static LinkedHashMap<ExecutionIndex, EiData> eiMap = new LinkedHashMap<>();
    private static final Random rng = new Random();
    private static final EiManualMutateGuidance genGuidance = new EiManualMutateGuidance(eiMap, rng);
    private static final JavaScriptCodeGenerator jsGen = new JavaScriptCodeGenerator();
    private static String genContents;

    public static final Map<Integer, String> eventStrings = new HashMap<>();

    public static void main(String[] args) throws IOException {
        init();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8000), 0);
        server.createContext("/ei", new ResponseHandler("ei") {
            @Override
            public String onGet() {
                // For ease of parsing, we send the random byte followed by the last event string, followed by
                // the execution index; each item separated by a colon.
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<ExecutionIndex, EiData> entry : eiMap.entrySet()) {
                    EiData value = entry.getValue();
                    sb.append(value.choice);
                    sb.append(":");
                    sb.append(value.stackTrace);
                    sb.append(":");
                    ExecutionIndex ei = entry.getKey();
                    sb.append(ei.toString());
                    sb.append("\n");
                }
                return sb.toString();
            }

            @Override
            public String onPost(BufferedReader reader) throws IOException {
                // POST expects parameters in a similar fashion, i.e. each line is int, space, EI array
                LinkedHashMap<ExecutionIndex, EiData> newEiMap = new LinkedHashMap<>();
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
                    ExecutionIndex key = new ExecutionIndex(eiArr);
                    newEiMap.put(key, new EiData(eiMap.get(key).stackTrace, data));
//                    System.out.println(Arrays.toString(eiArr));
                }
                eiMap = newEiMap;
                // who cares about abstraction barriers lmao
                genGuidance.eiMap = eiMap;
                return "OK";
            }
        });
        server.createContext("/generator",
                new ResponseHandler("generator") {
                    @Override
                    public String onGet() {
                        return getGenContents();
                    }

                    @Override
                    public String onPost(BufferedReader reader) {
                        // Rerun the generator and return the contents
                        dummy();
                        System.out.println("Updated generator contents (map is of size " + eiMap.size() + ")");
                        return getGenContents();
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
        SingleSnoop.setCallbackGenerator(genGuidance::generateCallBack);
//        String target = JavaScriptCodeGenerator.class.getName() + "#generate";
        String target = Server.class.getName() + "#dummy";
        SingleSnoop.startSnooping(target);
        System.out.println(SingleSnoop.entryPoints);
        // Needed for some jank call tracking
        dummy();
        System.out.println("Initial map is of size " + eiMap.size());
    }

    /**
     * Serves as an entry point to the tracking of the EI call stack.
     * Returns should probably stop tracking at runGenerator to avoid an oob exception.
     */
    private static void dummy() {
        genGuidance.reset();
        runGenerator();
    }

    private static String getGenContents() {
        return genContents.substring(0, Math.min(genContents.length(), 1024));
    }

    /**
     * Reruns the generator to update the generator string.
     * NOT ACTUALLY THE ENTRY POINT - USE dummy INSTEAD
     *
     * See Zest fuzzing loop.
     * https://github.com/rohanpadhye/JQF/blob/0152e82d4eb414b06438dec3ef0322135318291a/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java#L159
     */
    private static void runGenerator() {
        StreamBackedRandom randomFile = new StreamBackedRandom(genGuidance.getInput(), Long.BYTES);
        SourceOfRandomness random = new FastSourceOfRandomness(randomFile);
        GenerationStatus genStatus = new NonTrackingGenerationStatus(random);
        genContents = jsGen.generate(random, genStatus);
        System.out.println("generator produced: " + getGenContents());
    }

    public static String eventToString(TraceEvent e) {
        if (e instanceof BranchEvent) {
            BranchEvent b = (BranchEvent) e;
            return eventStrings.computeIfAbsent(
                    b.getIid(),
                    _k -> String.format("(branch) %s#%s()@%d [%d]", b.getContainingClass(), b.getContainingMethodName(),
                            b.getLineNumber(), b.getArm())
            );
        } else if (e instanceof CallEvent) {
            CallEvent c = (CallEvent) e;
            return eventStrings.computeIfAbsent(
                    c.getIid(),
                    _k -> String.format("(call) %s#%s()@%d --> %s", c.getContainingClass(), c.getContainingMethodName(),
                                c.getLineNumber(), c.getInvokedMethodName())
            );

        } else {
            return eventStrings.computeIfAbsent(
                    e.getIid(),
                    _k -> String.format("(other) %s#%s()@%d", e.getContainingClass(), e.getContainingMethodName(),
                            e.getLineNumber())
            );
        }
    }

    private static abstract class ResponseHandler implements HttpHandler  {
        private final String name;

        public ResponseHandler(String name) {
            this.name = name;
        }

        @Override
        public final void handle(HttpExchange httpExchange) throws IOException {
            String method = httpExchange.getRequestMethod();
            Headers headers = httpExchange.getResponseHeaders();
//            System.out.println(method + " /" + name);
            headers.add("Access-Control-Allow-Origin", "*");
            headers.add("Access-Control-Allow-Methods","GET,POST,OPTIONS");
//            headers.add("Access-Control-Allow-Headers","Access-Control-Allow-Origin,Content-Type");
            String response = "";
            switch (method) {
                case "GET":
                    response = onGet();
                    if (response.length() == 0) {
                        httpExchange.sendResponseHeaders(204, -1);
                    } else {
                        httpExchange.sendResponseHeaders(200, response.length());
                    }
                    break;
                case "POST":
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(httpExchange.getRequestBody()))) {
                        response = onPost(reader);
                    }
                    if (response.length() == 0) {
                        httpExchange.sendResponseHeaders(204, -1);
                    } else {
                        httpExchange.sendResponseHeaders(200, response.length());
                    }
                    break;
                case "OPTIONS":
                    httpExchange.sendResponseHeaders(204, -1);
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
