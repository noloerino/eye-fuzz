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
//        int n = genInt(sourceOfRandomness);
//        System.out.print("n = " + Integer.toHexString(n) + " (");
//        for (int i = 0; i < 4; i++) {
//            System.out.print(i + ": " + Integer.toHexString(((int) getByte(n, i)) & 0xFF) + (i != 3 ? " | " : ""));
//        }
//        System.out.println(")");
        // Generate a byte and an int to advance the generator
//        sourceOfRandomness.nextByte(Byte.MIN_VALUE, Byte.MAX_VALUE);
        char c = sourceOfRandomness.nextChar(Character.MIN_VALUE, Character.MAX_VALUE);
        sourceOfRandomness.nextInt();
        System.out.print("c = " + Integer.toBinaryString(c) + " (");
        for (int i = 0; i < 2; i++) {
            System.out.print(i + ": " + getByte(c, i) + (i != 1 ? " | " : ""));
        }
        System.out.println(")");
        return Integer.toBinaryString(c);
    }

    public int genInt(SourceOfRandomness sourceOfRandomness) {
        return sourceOfRandomness.nextInt();
    }

    private static byte getByte(int n, int i) {
        return (byte) ((n >> (i * 8)) & 0xFF);
    }
}
