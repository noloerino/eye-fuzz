import edu.berkeley.cs.jqf.fuzz.guidance.GuidanceException;
import edu.berkeley.cs.jqf.fuzz.guidance.Result;
import edu.berkeley.cs.jqf.fuzz.guidance.TimeoutException;
import edu.berkeley.cs.jqf.fuzz.junit.TrialRunner;
import org.junit.AssumptionViolatedException;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;

/**
 * Jacoco requires a Runnable instance for data collection. This method wraps JUnit
 * boilerplate to fit in the Jacoco framework.
 *
 * It also needs to be in Java for API compatibility reasons.
 */
public class TestWrapper<T> implements Runnable {

    private final T genOutput;
    private final Class<?> testClass;
    private final String testMethod;
    private final Class<T> genOutClass;

    public TestWrapper(T genOutput, Class<?> testClass, String testMethod, Class<T> genOutClass) {
        this.genOutput = genOutput;
        this.testClass = testClass;
        this.testMethod = testMethod;
        this.genOutClass = genOutClass;
    }

    public Result lastTestResult = Result.INVALID;

    public void run() {
        // TODO generalize by saving current obj rather than serialized string
        try {
            FrameworkMethod method = new FrameworkMethod(testClass.getMethod(testMethod, genOutClass));
            TrialRunner testRunner = new TrialRunner(testClass, method, new Object[] { genOutput });
            // Handle exceptions (see FuzzStatement)
            // https://github.com/rohanpadhye/JQF/blob/master/fuzz/src/main/java/edu/berkeley/cs/jqf/fuzz/junit/quickcheck/FuzzStatement.java
            Class<?>[] expectedExceptions = method.getMethod().getExceptionTypes();
            Result result;
            try {
                testRunner.run();
                result = Result.SUCCESS;
            } catch (AssumptionViolatedException e) {
                result = Result.INVALID;
            } catch (TimeoutException e) {
                result = Result.TIMEOUT;
            } catch (GuidanceException e) {
                throw e; // Propagate error so we can quit
            } catch (Throwable t) {
                result = Result.FAILURE;
                for (Class<?> e : expectedExceptions) {
                    if (e.isAssignableFrom(t.getClass())) {
                        result = Result.SUCCESS;
                    } else {
                        result = Result.FAILURE;
                    }
                }
            }
            lastTestResult = result;
        } catch (NoSuchMethodException | InitializationError e) {
            e.printStackTrace();
            lastTestResult = Result.FAILURE;
        }
    }
}
