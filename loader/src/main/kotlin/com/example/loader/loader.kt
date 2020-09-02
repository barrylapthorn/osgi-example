package com.example.loader

import org.osgi.framework.Bundle
import org.osgi.framework.Constants
import org.osgi.framework.launch.Framework
import org.osgi.framework.launch.FrameworkFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList
import kotlin.system.exitProcess

object Main {
    private var framework: Framework? = null

    @kotlin.Throws(Exception::class)
    @kotlin.jvm.JvmStatic
    fun main(args: Array<String>) {
        // Must specify a bundle directory.
        if (args.isEmpty()) {
            println("Usage: <bundle-directory>")
            exitProcess(1)
        }

        val bundleDir = Paths.get(args[0])
        if (!Files.isDirectory(bundleDir)) {
            println("Specified path is not a directory: $bundleDir")
            exitProcess(1)
        }

        val jars = getJars(bundleDir)

        // If no bundle JAR files are in the directory, then exit.
        if (jars.isEmpty()) {
            println("No bundles to install in $bundleDir")
            exitProcess(1)
        }

        // If there are bundle JAR files to install, then register a
        // shutdown hook to make sure the OSGi framework is cleanly
        // shutdown when the VM exits.
        Runtime.getRuntime().addShutdownHook(object : Thread() {
            override fun run() {
                try {
                    framework?.stop()
                    framework?.waitForStop(0)
                } catch (ex: Exception) {
                    System.err.println("Error stopping framework: $ex")
                }
            }
        })

        try {
            // Create, configure, and start an OSGi framework instance
            // using the ServiceLoader to get a factory.
            val propertyMap = System.getProperties().entries
                    .associate { it.key.toString() to it.value.toString() }
                    .toMutableMap()

            propertyMap[Constants.FRAMEWORK_STORAGE_CLEAN] = "onFirstInit"

            framework = frameworkFactory.newFramework(propertyMap)
            framework?.start()

            // Get the bundleContext of 'bundle 0' - the framework (e.g. Apache Felix)
            val bundleContext = framework?.bundleContext!!

            // Install bundles
            val bundles = jars.mapNotNull { bundleContext.installBundle(it.toUri().toString()) }

            // BL: See if we have an 'entry point' - remember this is just arbitrary - we could
            // use 'Corda-Main' or some other meta data key.
            val mainBundle = bundles.firstOrNull { it.headers?.get("Main-Class") != null }

            bundles.forEach { startBundle(it) }

            // If a bundle exists with an entry point ("Main-Class"),
            // then load the class and invoke its static main method.
            invokeEntryPointBundle(mainBundle, args)

            // Wait for framework to stop.
            framework?.waitForStop(0)
            exitProcess(0)
        } catch (ex: Exception) {
            System.err.println("Error starting framework: $ex")
            ex.printStackTrace()
            exitProcess(0)
        }
    }

    private fun invokeEntryPointBundle(bundleToInvoke: Bundle?, args: Array<String>) {
        // BL:  this is completely "custom code" - we could choose to look for
        //  "Corda-Main" or something similar.  Basically, an 'entry point'
        // to start off execution.

        if (bundleToInvoke == null)
            return

        val mainClassName: String? = bundleToInvoke.headers.get("Main-Class") as String

        if (mainClassName == null) {
            System.err.println("Main class not found: $mainClassName")
            return
        }

        val mainClass: Class<*> = bundleToInvoke.loadClass(mainClassName)
        try {
            val method = mainClass.getMethod("main", Array<String>::class.java)
            val mainArgs = arrayOfNulls<String>(args.size - 1)
            System.arraycopy(args, 1, mainArgs, 0, mainArgs.size)
            method.invoke(null, mainArgs)
        } catch (ex: Exception) {
            System.err.println("Error invoking main method: " + ex + " cause = " + ex.cause)
        }
    }

    private fun startBundle(it: Bundle) {
        if (!isFragment(it)) {
            println("Starting ${it.symbolicName} v${it.version}")
            it.start()
        }
    }

    private fun getJars(dirName: Path): List<Path> {
        return Files.list(dirName).toList().filter {
            it.toString().toLowerCase().endsWith(".jar")
        }
    }

    /**
     * Fragment bundles cannot be started on their own.
     *
     * "Manifest header identifying the symbolic name of another bundle for which
     * that the bundle is a fragment."
     */
    private fun isFragment(bundle: Bundle): Boolean = bundle.headers.get(Constants.FRAGMENT_HOST) != null

    /**
     * Simple method to parse META-INF/services file for framework factory.
     * Currently, it assumes the first non-commented line is the class name
     * of the framework factory implementation.
     * @return The created <tt>FrameworkFactory</tt> instance.
     * @throws Exception if any errors occur.
     */
    @get:Throws(Exception::class)
    private val frameworkFactory: FrameworkFactory
        get() {
            val url: URL? = Main::class.java.classLoader.getResource(
                    "META-INF/services/org.osgi.framework.launch.FrameworkFactory")
            if (url != null) {
                val br = BufferedReader(InputStreamReader(url.openStream()))
                br.use { it ->
                    var line = it.readLine()
                    while (line != null) {
                        line = line.trim { it <= ' ' }
                        // Try to load first non-empty, non-commented line.
                        if (line.isNotEmpty() && line[0] != '#') {
                            return Class.forName(line).newInstance() as FrameworkFactory
                        }
                        line = it.readLine()
                    }
                }
            }
            throw Exception("Could not find framework factory.")
        }
}