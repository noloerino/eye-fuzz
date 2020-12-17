import com.pholser.junit.quickcheck.generator.Generator
import kotlin.system.exitProcess

fun main(args: Array<String>) {
//    var serialFunc: Method? = null
    if (args.size != 4) {
        println("arguments: GENERATOR_CLASS_NAME TEST_CLASS_NAME TEST_METHOD_NAME COVERAGE_CLASS_NAMES")
        exitProcess(-1)
//        if (args.size != 6) {
//            // TODO make port number an argument
//            println("arguments: GENERATOR_CLASS_NAME TEST_CLASS_NAME TEST_METHOD_NAME COVERAGE_CLASS_NAMES [SERIALIZER_CLASS] [SERIALIZER_FUNC]")
//            exitProcess(-1)
//        }
//        val serialClass = Class.forName(args[4]).newInstance()
//        val genType = (Class.forName(args[0]).genericSuperclass as ParameterizedType).actualTypeArguments[0].javaClass
//        serialFunc = serialClass.javaClass.getMethod(args[5], genType)
    }
    val loader = ClassLoader.getSystemClassLoader()
    val genClass = loader.loadClass(args[0])
    val genInstance = genClass.newInstance() as Generator<*>
    val covClassNames = args[3].split(",").filter { it.isNotBlank() }
    val testClassName = args[1]
    val testMethodName = args[2]
    val server = Server(genInstance, testClassName, testMethodName, covClassNames)
    server.start()
}
