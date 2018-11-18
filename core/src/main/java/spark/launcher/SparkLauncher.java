package spark.launcher;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class SparkLauncher {

    /** The Spark master. */
    public static final String SPARK_MASTER = "spark.master";

    /** The Spark deploy mode. */
    public static final String DEPLOY_MODE = "spark.submit.deployMode";

    /** Configuration key for the driver memory. */
    public static final String DRIVER_MEMORY = "spark.driver.memory";
    /** Configuration key for the driver class path. */
    public static final String DRIVER_EXTRA_CLASSPATH = "spark.driver.extraClassPath";
    /** Configuration key for the driver VM options. */
    public static final String DRIVER_EXTRA_JAVA_OPTIONS = "spark.driver.extraJavaOptions";
    /** Configuration key for the driver native library path. */
    public static final String DRIVER_EXTRA_LIBRARY_PATH = "spark.driver.extraLibraryPath";

    /** Configuration key for the executor memory. */
    public static final String EXECUTOR_MEMORY = "spark.executor.memory";
    /** Configuration key for the executor class path. */
    public static final String EXECUTOR_EXTRA_CLASSPATH = "spark.executor.extraClassPath";
    /** Configuration key for the executor VM options. */
    public static final String EXECUTOR_EXTRA_JAVA_OPTIONS = "spark.executor.extraJavaOptions";
    /** Configuration key for the executor native library path. */
    public static final String EXECUTOR_EXTRA_LIBRARY_PATH = "spark.executor.extraLibraryPath";
    /** Configuration key for the number of executor CPU cores. */
    public static final String EXECUTOR_CORES = "spark.executor.cores";

    static final String PYSPARK_DRIVER_PYTHON = "spark.pyspark.driver.python";

    static final String PYSPARK_PYTHON = "spark.pyspark.python";

    static final String SPARKR_R_SHELL = "spark.r.shell.command";

    /** Logger name to use when launching a child process. */
    public static final String CHILD_PROCESS_LOGGER_NAME = "spark.launcher.childProcLoggerName";

    /**
     * A special value for the resource that tells Spark to not try to process the app resource as a
     * file. This is useful when the class being executed is added to the application using other
     * means - for example, by adding jars using the package download feature.
     */
    public static final String NO_RESOURCE = "spark-internal";

    /**
     * Maximum time (in ms) to wait for a child process to connect back to the launcher server
     * when using @link{#start()}.
     */
    public static final String CHILD_CONNECTION_TIMEOUT = "spark.launcher.childConectionTimeout";

    /** Used internally to create unique logger names. */
    private static final AtomicInteger COUNTER = new AtomicInteger();

    static final Map<String, String> launcherConfig = new HashMap<>();

}
