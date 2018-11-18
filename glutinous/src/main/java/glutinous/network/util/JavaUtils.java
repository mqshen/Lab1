package glutinous.network.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

public class JavaUtils {
    private static final Logger logger = LoggerFactory.getLogger(JavaUtils.class);

    /** Closes the given object, ignoring IOExceptions. */
    public static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException e) {
            logger.error("IOException should not have been thrown.", e);
        }
    }
}
