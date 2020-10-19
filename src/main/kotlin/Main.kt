fun main() {
    val server =
//            Server(XmlDocumentGenerator(), XMLDocumentUtils::documentToString)
             Server<String>(JavaScriptCodeGenerator(), "DummyTest", "testWithGenerator")
//            Server<String>(NumberGenerator(), "NumberTest", "testWithGenerator")
    server.start()
}
