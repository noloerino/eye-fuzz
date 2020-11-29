import edu.berkeley.cs.jqf.fuzz.Fuzz;

// Dummy test driver
public class IntTestDriver {
    // Must be boxed type to make reflection happy
    @Fuzz
    public void testDummy(java.lang.Integer _n) {}
}
