import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerOptions;
import com.google.javascript.jscomp.Result;
import com.google.javascript.jscomp.SourceFile;
import com.pholser.junit.quickcheck.From;
import edu.berkeley.cs.jqf.examples.js.JavaScriptCodeGenerator;
import edu.berkeley.cs.jqf.fuzz.Fuzz;
import edu.berkeley.cs.jqf.fuzz.JQF;
import org.junit.runner.RunWith;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

@RunWith(JQF.class)
public class DummyTest {
    static {
        // Disable all logging by Closure passes, to speed up fuzzing
        java.util.logging.LogManager.getLogManager().reset();
    }

    // Compiler, options, and predefined JS environment
    private Compiler compiler = new Compiler(new PrintStream(new ByteArrayOutputStream(), false));
    private CompilerOptions options = new CompilerOptions();
    private SourceFile externs = SourceFile.fromCode("externs", "");

    /** Compiles an input and returns its result */
    private Result compile(SourceFile input) {
        Result result = compiler.compile(externs, input, options);
        assumeTrue(result.success); // Semantic validity check
        return result;
    }

    // Needed to expose to runner server
    public static String generated = "";

    @Fuzz
    public void testWithGenerator(@From(JavaScriptCodeGenerator.class) String code) {
        generated = code;
//        SourceFile input = SourceFile.fromCode("input", code);
//        compile(input);
    }
}
