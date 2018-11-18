package spark.unsafe.array;

public class ByteArrayMethods {



    // Some JVMs can't allocate arrays of length Integer.MAX_VALUE; actual max is somewhat smaller.
    // Be conservative and lower the cap a little.
    // Refer to "http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/tip/src/share/classes/java/util/ArrayList.java#l229"
    // This value is word rounded. Use this value if the allocated byte arrays are used to store other
    // types rather than bytes.
    public static int MAX_ROUNDED_ARRAY_LENGTH = Integer.MAX_VALUE - 15;

}
