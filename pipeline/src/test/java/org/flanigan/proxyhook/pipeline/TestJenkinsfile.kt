package org.flanigan.proxyhook.pipeline

import com.cloudbees.groovy.cps.impl.CpsCallableInvocation
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.lesfurets.jenkins.unit.cps.BasePipelineTestCPS
import com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration
import groovy.lang.Closure
import org.codehaus.groovy.runtime.MethodClosure
import org.junit.Before
import org.junit.Test

import java.io.File
import java.util.HashMap

import com.lesfurets.jenkins.unit.MethodSignature.method
import com.lesfurets.jenkins.unit.global.lib.GitSource.gitSource
import com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import java.lang.Boolean.TRUE
import java.util.function.Function

// try 'extends BasePipelineTest' for debugging in case of weird Groovy exceptions
class TestJenkinsfile : BasePipelineTestCPS() {

    companion object {
        private val LIB_PATH = "target/libs"
    }

    @Before
    override fun setUp() {
        super.setUp()

        val library = library()
                .name("zanata-pipeline-library")
                .retriever(gitSource("https://github.com/zanata/zanata-pipeline-library"))
                // uncomment to use already-downloaded (perhaps modified) copy instead of git:
                //                .retriever(localSource(LIB_PATH))
                .targetPath(LIB_PATH)
                .defaultVersion("master")
                .allowOverride(true)
                .implicit(false)
                .build()
        helper.registerSharedLibrary(library)

        // set up mock methods (Note: built-ins are in BasePipelineTest.setUp)
        helper.registerAllowedMethod("archive", listOf(Map::class.java), null)
        helper.registerAllowedMethod("archive", listOf(Object::class.java), null)
        helper.registerAllowedMethod("hipchatSend", listOf(Map::class.java), null)
        helper.registerAllowedMethod("junit", listOf(Map::class.java), null)
        helper.registerAllowedMethod("lock", listOf(Map::class.java, Closure::class.java), null)
        helper.registerAllowedMethod("lock", listOf(String::class.java, Closure::class.java), null)
        helper.registerAllowedMethod("milestone", listOf(), null)
        helper.registerAllowedMethod("stash", listOf(Map::class.java), null)
        helper.registerAllowedMethod("timestamps", listOf(Closure::class.java), null)
        helper.registerAllowedMethod("unstash", listOf(Map::class.java), null)
        helper.registerAllowedMethod("unstash", listOf(String::class.java), null)
        helper.registerAllowedMethod("withEnv", listOf(List::class.java, Closure::class.java), null)
        helper.registerAllowedMethod("wrap", listOf(Map::class.java, Closure::class.java), null)

        helper.registerAllowedMethod(method("findFiles", Map::class.java),
                Function { args: Map<String, *> ->
                    val glob = args["glob"].toString()
                    if (glob == "**/build/test-results/*.xml") {
                        return@Function arrayOf(File("server/build/test-results/Test.xml"))
                    }
                    throw RuntimeException("Unmocked invocation")
                })
        helper.registerAllowedMethod(method("sh", Map::class.java),
                Function { args: Map<String, *> ->
                    if (TRUE == args["returnStatus"]) {
                        return@Function 0
                    }
                    0
                } as Function<*, *>)
        // PipelineUnit(withCredentialsInterceptor) can't handle a List<Map>
        helper.registerAllowedMethod("withCredentials",
                listOf(List::class.java, Closure::class.java),
                object : Closure<Any>(null) {
                    override fun call(vararg args: Any): Any {
                        val closure = args[1] as Closure<*>
                        return closure.call()
                    }
                })

        // environment variables
        val env = HashMap<String, String>()
        env.put("BUILD_URL", "http://example.com/job/JobName/123")
        env.put("JOB_NAME", "JobName")
        env.put("BRANCH_NAME", "PR-456")
        env.put("BUILD_NUMBER", "123")
        env.put("EXECUTOR_NUMBER", "1")
        env.put("DEFAULT_NODE", "master")
        env.put("NODE_NAME", "jenkins-pipeline-unit")

        // these steps will be passed by reference to library methods
        val steps = HashMap<String, Closure<*>>()
	steps.put("codecov", Closure.IDENTITY)
	steps.put("hipchatSend", Closure.IDENTITY)
	steps.put("echo", Closure.IDENTITY)
        steps.put("sh", Closure.IDENTITY)
        // we need this for CPS mode
        MethodClosure.ALLOW_RESOLVE = true

        // global variables
        binding.setProperty("env", env)
        binding.setProperty("steps", steps)
        binding.setProperty("params", ImmutableMap.of("LABEL", "master"))
        binding.setProperty("LABEL", "master")

        // these objects are just used as parameters
        binding.setProperty("scm", ImmutableMap.of<String, Any>())
        binding.setProperty("manager", ImmutableMap.of<String, Any>())
    }

    @Test
    fun shouldExecuteWithoutErrors() {
        try {
            // load and execute the Jenkinsfile
            loadScript("../Jenkinsfile")
            printCallStack()
            assertJobStatusSuccess()
            // TODO add assertions about call stack (but not too fragile)
        } catch (e: CpsCallableInvocation) {
            // if the script fails, we need the call stack to tell us where the problem is
            // (CpsCallableInvocation tells us very little)
            System.err.println("CPS call stack:")
            helper.callStack.forEach { System.err.println() }
            throw e
        }
    }

}
