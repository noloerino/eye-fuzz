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
        var responseCode = 503
        var responseLen: Long = -1
        fun okResponse() {
            if (response.isEmpty()) {
                responseCode = 204
                responseLen = -1
            } else {
                responseCode = 200
                responseLen = response.length.toLong()
            }
        }
        try {
            when (method) {
                "GET" -> {
                    response = onGet()
                    okResponse()
                }
                "POST" -> {
                    BufferedReader(InputStreamReader(httpExchange.requestBody)).use { reader -> response = onPost(reader) }
                    okResponse()
                }
                "PATCH" -> {
                    BufferedReader(InputStreamReader(httpExchange.requestBody)).use { reader -> response = onPatch(reader) }
                    okResponse()
                }
                "OPTIONS" -> {
                    responseCode = 204
                    responseLen = -1
                }
                else -> {
                    responseCode = 501
                    responseLen = 0
                }
            }
        } catch (e: Exception) {
            eprintln("Exception in handling $method /$name")
            eprintln(e.stackTraceToString())
            responseCode = 503
            responseLen = 0
            response = ""
        } finally {
            httpExchange.sendResponseHeaders(responseCode, responseLen)
            httpExchange.responseBody.use { out -> out.write(response.toByteArray()) }
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