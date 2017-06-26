#!/usr/bin/env groovy
import groovy.transform.Field

import static java.util.concurrent.TimeUnit.SECONDS

@Field String logFile = 'proxyhook-client.log'
@Field String clientJarGlob = 'proxyhook-client-fat.jar'
@Field String clientJarRegex = clientJarGlob.replace('*', '.*')

def findClientPid() {
    def process = ['pgrep', '--full', "java .*-jar $clientJarRegex"].execute()
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
        """exec nohup >$logFile 2>&1 java -Xmx32M -jar $clientJarGlob \
           wss://proxyhook-zanata.rhcloud.com:8443/listen \
           https://zanata-jenkins.rhev-ci-vms.eng.rdu2.redhat.com/github-webhook/"""
    ].execute()

    if (proc.waitFor(5, SECONDS)) {
        println "Client failed (exit value: ${proc.exitValue()}). Output follows:"
    } else {
        println "Client is now running. Initial output follows:"
    }
    println new File(logFile).text
    return proc
}

if (binding.hasVariable('args') && args.length != 0 && args[0] == 'kill') {
    def pid = findClientPid()
    println "Killing client with pid $pid"
    def proc = "kill -9 $pid".execute()
    if (proc.waitFor() != 999) {
        println proc.in.text
        println proc.err.text
    }
} else {
    def pid = findClientPid()
    if (pid) {
        println "ProxyHookClient is already running, with pid $pid"
    } else {
        startClient()
        // TODO (Java 9) use Process.getPid(), maybe write it to a .pid file. (de.flapdoodle.embed.process could help on Java 8.)
        pid = findClientPid()
        println "ProxyHookClient pid is $pid"
    }
}
