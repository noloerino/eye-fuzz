import com.pholser.junit.quickcheck.From;
import edu.berkeley.cs.jqf.fuzz.Fuzz;
import edu.berkeley.cs.jqf.fuzz.JQF;
import org.junit.runner.RunWith;

@RunWith(JQF.class)
public class NumberTest {

    @Fuzz
    public void testWithGenerator(@From(NumberGenerator.class) String input) {
        System.out.print(input + " -> ");
        int n = Integer.parseInt(input);
        for (int i = 0; i < 4; i++) {
            System.out.print(i + ": " + getByte(n, i) + " | ");
        }
        System.out.println();
    }

    private static byte getByte(int n, int i) {
        return (byte) ((n >> (i * 8)) & 0xFF);
    }
}
