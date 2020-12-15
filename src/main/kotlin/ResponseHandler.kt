import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.system.exitProcess

abstract class ResponseHandler(val name: String) : HttpHandler {

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
        try {
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
        } catch (e: Exception) {
            eprintln("Exception in handling $method /$name")
            e.printStackTrace()
            httpExchange.sendResponseHeaders(503, 0)
            exitProcess(1)
        } finally {
            httpExchange.close()
        }
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