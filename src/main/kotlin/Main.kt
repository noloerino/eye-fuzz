fun main() {
    val server =
//            Server(XmlDocumentGenerator(), XMLDocumentUtils::documentToString)
             Server<String>(JavaScriptCodeGenerator(), "DummyTest", "testWithGenerator")
    server.start()
}
