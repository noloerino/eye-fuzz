import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * Manages the HTTP connection to dispatch the generator and fuzzer as needed.
 */
public class Server {
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 8000), 0);
        server.createContext("/generator", httpExchange -> {
            System.out.println("Hit /generator");
            String method = httpExchange.getRequestMethod();
            if (method.equals("GET")) {
                String genContents = "var k_1; k_1 ++;\n";
                httpExchange.sendResponseHeaders(501, genContents.length());
                try (OutputStream out = httpExchange.getResponseBody()) {
                    out.write(genContents.getBytes());
                }
            } else {
                httpExchange.sendResponseHeaders(501, 0);
                try (OutputStream out = httpExchange.getResponseBody()) {
                }
            }
        });
        server.createContext("/coverage", httpExchange -> {
            System.out.println("Hit /coverage");
            httpExchange.sendResponseHeaders(501, 0);
            try (OutputStream out = httpExchange.getResponseBody()) {
            }
        });
        server.start();
    }
}
