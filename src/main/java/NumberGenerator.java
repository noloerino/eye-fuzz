import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;

/**
 * Just generates numbers. Used to test coalescing of byte generation into a single int or larger data types.
 * This demonstrates that the generator is little endian.
 */
public class NumberGenerator extends Generator<String> {
    public NumberGenerator() {
        super(String.class);
    }

    @Override
    public String generate(SourceOfRandomness sourceOfRandomness, GenerationStatus generationStatus) {
        int n = genInt(sourceOfRandomness);
        System.out.print("n = " + Integer.toHexString(n) + " (");
        for (int i = 0; i < 4; i++) {
            System.out.print(i + ": " + Integer.toHexString(((int) getByte(n, i)) & 0xFF) + (i != 3 ? " | " : ""));
        }
        System.out.println(")");
        return Integer.toString(n);
    }

    public int genInt(SourceOfRandomness sourceOfRandomness) {
        return sourceOfRandomness.nextInt();
    }

    private static byte getByte(int n, int i) {
        return (byte) ((n >> (i * 8)) & 0xFF);
    }
}
