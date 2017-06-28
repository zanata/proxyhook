#!/usr/bin/env groovy
import static java.util.concurrent.TimeUnit.SECONDS

// get the JVM environment variables

// eg /home/jenkins/proxyhook
clientDir = System.getenv('PROXYHOOK_CLIENT_HOME')
// eg wss://proxyhook-example.rhcloud.com:8443/listen
serverUrl = System.getenv('PROXYHOOK_SERVER')

// but prefer Jenkins environment variable (if running inside Jenkins and var has been set)
try {
    globalProps = jenkins.model.Jenkins.instance.globalNodeProperties
    envNodes = globalProps.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)
    if (envNodes) {
        envVars = envNodes.get(0).envVars
        if (envVars.PROXYHOOK_CLIENT_HOME) {
            clientDir = envVars.PROXYHOOK_CLIENT_HOME
        }
        if (envVars.PROXYHOOK_SERVER) {
            serverUrl = envVars.PROXYHOOK_SERVER
        }
    }
} catch (MissingPropertyException ignored) {
}
if (clientDir == null) {
    clientDir = System.getProperty('user.home')
}

logFile = "$clientDir/proxyhook-client.log"
clientJarGlob = "proxyhook-client-fat.jar"
clientJarFile = new File(clientDir, clientJarGlob)
if (!clientJarFile.exists()) {
    println "proxyhook client jar $clientJarFile not found."
    return
}
clientJarRegex = clientJarGlob.replace('*', '.*')

def findClientPid() {
    def process = ['pgrep', '--full', "java .*-jar .*$clientJarRegex"].execute()
    if (process.waitFor() == 0) {
        def pid = process.in.text
        return pid
    } else if (process.waitFor() == 1) {
        return null
    } else {
        def error = process.err.text
        println "pgrep error: $error"
        return null
    }
}

Process startClient() {
    println "Starting ProxyHook client. Logging to $logFile"
    def proc = [
        'sh', '-c',
        // FIXME get these from Jenkins, or env vars
        """exec java >$logFile 2>&1 -Xmx32M -jar $clientJarFile \
           $serverUrl \
           http://localhost:8080/github-webhook/"""
    ].execute()

    if (proc.waitFor(5, SECONDS)) {
        println "Client failed (exit value: ${proc.exitValue()}). Output follows:"
    } else {
        println "Client is now running. Initial output follows:"
    }
    println new File(logFile).text
    return proc
}

def killProcess(pid) {
    println "Killing process with pid $pid"
    def cmd = "kill $pid"
    println cmd
    def proc = cmd.execute()
    if (proc.waitFor() != 0) {
        println proc.in.text
        println proc.err.text
    }
}

// NB as a child process, the client may be killed when Jenkins exits, but not when it restarts itself
def oldPid = findClientPid()
if (oldPid) {
    println "Found old ProxyHook client with pid $oldPid"
}

if (binding.hasVariable('args') && args.length != 0 && args[0] == 'kill') {
    if (oldPid) {
        killProcess(oldPid)
    }
} else {
    // start the new client first, so that it is running before we stop the old client
    startClient()
    if (oldPid) {
        killProcess(oldPid)
        // wait so that findClientPid won't see the old process
        sleep(300)
    }
// TODO (Java 9) use Process.getPid(), maybe write it to a .pid file. (de.flapdoodle.embed.process could help on Java 8.)
    newPid = findClientPid()
    println "New ProxyHook client pid is $newPid"
}
