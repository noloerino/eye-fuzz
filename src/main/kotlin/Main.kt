import edu.berkeley.cs.jqf.examples.xml.XMLDocumentUtils
import edu.berkeley.cs.jqf.examples.xml.XmlDocumentGenerator

fun main() {
    val server =
//            Server(XmlDocumentGenerator(), XMLDocumentUtils::documentToString)
             Server<String>(JavaScriptCodeGenerator())
    server.run()
}
