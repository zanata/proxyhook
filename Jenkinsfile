#!/usr/bin/env groovy

@Library('zanata-pipeline-library@master')
import org.zanata.jenkins.Notifier
import org.zanata.jenkins.PullRequests
import static org.zanata.jenkins.StackTraces.getStackTrace


import groovy.transform.Field


// The first milestone step starts tracking concurrent build order
milestone()


PullRequests.ensureJobDescription(env, manager, steps)

@Field
def notify
// initialiser must be run separately (bindings not available during compilation phase)
notify = new Notifier(env, steps)

// use timestamps for Jenkins logs
timestamps {
  // allocate a node for build+unit tests
  node() {
    echo "running on node ${env.NODE_NAME}"
    // generate logs in colour
    ansicolor {
      try {
        stage('Checkout') {
          // notify methods send instant messages about the build progress
          notify.started()

          // Shallow Clone does not work with RHEL7, which uses git-1.8.3
          // https://issues.jenkins-ci.org/browse/JENKINS-37229
          checkout scm

          // Clean the workspace
          sh "git clean -fdx"
        }
        stage('Build') {
          sh """./gradlew clean build shadowJar jacocoTestReport
          """

          // archive build artifacts
          archive "**/build/libs/*.jar"


          // gather surefire results; mark build as unstable in case of failures
          junit(testResults: '**/build/test-results/*.xml')

          // parse Jacoco test coverage
          step([$class: 'JacocoPublisher'])

          // TODO codecov

          if (env.BRANCH_NAME == 'master') {
            step([$class: 'MasterCoverageAction'])
          } else if (env.BRANCH_NAME.startsWith('PR-')) {
            step([$class: 'CompareCoverageAction'])
          }

          // Reduce workspace size
          sh "git clean -fdx"
        }
        notify.testResults("UNIT", currentBuild.result)
      } catch (e) {
        notify.failed()
        currentBuild.result = 'FAILURE'
        throw e
      }
    }
  }
}
