#!/usr/bin/env groovy

@Field
public static final String PROJ_URL = 'https://github.com/zanata/proxyhook'

// Import pipeline library for utility methods & classes:
// ansicolor(), Notifier, PullRequests, Strings
@Field
public static final String PIPELINE_LIBRARY_BRANCH = 'ZNTA-2234-tag'

// GROOVY-3278:
//   Using referenced String constant as value of Annotation causes compile error
@Library('zanata-pipeline-library@ZNTA-2234-tag')
import org.zanata.jenkins.Notifier
import org.zanata.jenkins.PullRequests
import org.zanata.jenkins.ScmGit
import static org.zanata.jenkins.Reporting.codecov
import static org.zanata.jenkins.StackTraces.getStackTrace

import groovy.transform.Field

// The first milestone step starts tracking concurrent build order
milestone()

PullRequests.ensureJobDescription(env, manager, steps)

@Field
def pipelineLibraryScmGit

@Field
def mainScmGit

@Field
def notify
// initialiser must be run separately (bindings not available during compilation phase)

/* Only keep the 10 most recent builds. */
def projectProperties = [
  [
    $class: 'GithubProjectProperty',
    projectUrlStr: PROJ_URL
  ],
  [
    $class: 'BuildDiscarderProperty',
    strategy: [$class: 'LogRotator', numToKeepStr: '10']
  ],
]
properties(projectProperties)


// Decide whether the build should be tagged and deployed. If the result $tag
// is non-null, a corresponding git tag $tag has been created. If the build
// succeeds, we should push $tag and deploy the application. If the result
// is null, this is not the master branch, and we should not perform any
// release/deploy steps.
String makeTag() {
  if (env.BRANCH_NAME == 'master') {
    sh './gradlew setVersionFromBuild'
    def ver = readProperties('gradle.properties')?.version
    if (ver == null) return null
    def tag = 'v' + ver
    sh "git commit gradle.properties -m 'Update version for $ver' && git tag $tag"
    return tag
  } else {
    return null
  }
}

// use timestamps for Jenkins logs
timestamps {
  // allocate a node for build+unit tests
  node() {
    echo "running on node ${env.NODE_NAME}"
    pipelineLibraryScmGit = new ScmGit(env, steps, 'https://github.com/zanata/zanata-pipeline-library')
    pipelineLibraryScmGit.init(PIPELINE_LIBRARY_BRANCH)
    mainScmGit = new ScmGit(env, steps, PROJ_URL)
    mainScmGit.init(env.BRANCH_NAME)
    notify = new Notifier(env, steps, currentBuild,
        pipelineLibraryScmGit, mainScmGit, 'Jenkinsfile',
    )
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
          notify.startBuilding()
          def tag = makeTag()

          // TODO run detekt
          sh """./gradlew clean build shadowJar jacocoTestReport
          """

          // archive build artifacts
          archive "**/build/libs/*.jar"

          // gather surefire results; mark build as unstable in case of failures
          junit(testResults: '**/build/test-results/*.xml')
          notify.testResults("UNIT", currentBuild.result)

          if (currentBuild.result in ['SUCCESS', null]) {
            // parse Jacoco test coverage
            step([$class: 'JacocoPublisher'])

            if (env.BRANCH_NAME == 'master') {
              step([$class: 'MasterCoverageAction'])
            } else if (env.BRANCH_NAME.startsWith('PR-')) {
              step([$class: 'CompareCoverageAction'])
            }

            // send test coverage data to codecov.io
            codecov(env, steps, mainScmGit)

            if (tag) {
              // When https://issues.jenkins-ci.org/browse/JENKINS-28335 is done, use GitPublisher instead
              sshagent(['zanata-jenkins']) {
                def sshRepo = "git@github.com:zanata/proxyhook.git"
                // TODO remove fetch, activate push
                sh "git -c core.askpass=true fetch $sshRepo"
//                sh "git -c core.askpass=true push $sshRepo $tag"
              }

              // deploy client
              if (env.PROXYHOOK_CLIENT_HOME && env.PROXYHOOK_SERVER) {
                // deploy new version of client
                sh "cp client/client*-fat.jar ${env.PROXYHOOK_CLIENT_HOME}/proxyhook-client-fat.jar"
                sh "cp client/init.groovy.d/proxyhook_client.groovy $JENKINS_HOME/init.groovy.d/proxyhook_client.groovy"
                // restart the client
                sh "$JENKINS_HOME/init.groovy.d/proxyhook_client.groovy"
              }

              // deploy server
              sh "./gradlew :server:tarball -x clean -x shadowJar"
              if (env.PROXYHOOK_NAMESPACE && env.PROXYHOOK_APP) {
                // deploy new version of server
                withCredentials([usernamePassword(
                    credentialsId: 'openshift',
                    usernameVariable: 'OPENSHIFT_LOGIN',
                    passwordVariable: 'OPENSHIFT_PASSWORD')]) {
                  docker.image('bigm/rhc').inside("-v /root/.openshift -v /private") {
                    sh """rhc setup --rhlogin ${env.OPENSHIFT_LOGIN}
                      --password ${env.OPENSHIFT_PASSWORD}"""
                    sh """rhc deploy --app ${env.PROXYHOOK_APP}
                        --namespace ${env.PROXYHOOK_NAMESPACE}
                        server/build/server-deploy*.tar.gz"""
                  }
                }
              }
            }
            notify.successful()
          }

          // Reduce workspace size
          sh "git clean -fdx"
        }
      } catch (e) {
        notify.failed()
        currentBuild.result = 'FAILURE'
        throw e
      }
    }
  }
}
