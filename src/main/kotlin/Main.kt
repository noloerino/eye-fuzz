import com.pholser.junit.quickcheck.generator.Generator
import kotlin.system.exitProcess

fun main(args: Array<String>) {
//    var serialFunc: Method? = null
    if (args.size != 3) {
        if (args.size != 5) {
            // TODO make port number an argument
            println("arguments: GENERATOR_CLASS_NAME TEST_CLASS_NAME TEST_METHOD_NAME [SERIALIZER_CLASS] [SERIALIZER_FUNC]")
            exitProcess(-1)
        }
//        val serialClass = Class.forName(args[4]).newInstance()
//        val genType = (Class.forName(args[0]).genericSuperclass as ParameterizedType).actualTypeArguments[0].javaClass
//        serialFunc = serialClass.javaClass.getMethod(args[5], genType)
    }
//    println("cp: ${System.getProperty("java.class.path")}")
    val loader = ClassLoader.getSystemClassLoader()
//    println("loader: ${loader.getResource("CalendarTest")}")
//    println("loader self: ${loader.getResource("MainKt")}")
    val genClass = Class.forName(args[0], true, loader)
    val genInstance = genClass.newInstance() as Generator<*>
    val server = Server(genInstance, args[1], args[2])
//             Server(XmlDocumentGenerator(), XMLDocumentUtils::documentToString)
//             Server<String>(JavaScriptCodeGenerator(), "DummyTest", "testWithGenerator")
//             Server<String>(NumberGenerator(), "NumberTest", "testWithGenerator")
    server.start()
}
