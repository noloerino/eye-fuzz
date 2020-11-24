import com.pholser.junit.quickcheck.generator.Generator

fun main(args: Array<String>) {
    require(args.size == 3) {
        "arguments: <generator class name> <test class name> <test method name>"
    }
    val genClass = Class.forName(args[0])
    val genInstance = genClass.newInstance() as Generator<*>
    val server = Server(
            genInstance,
            args[1],
            args[2]
    )
//             Server(XmlDocumentGenerator(), XMLDocumentUtils::documentToString)
//             Server<String>(JavaScriptCodeGenerator(), "DummyTest", "testWithGenerator")
//             Server<String>(NumberGenerator(), "NumberTest", "testWithGenerator")
    server.start()
}
