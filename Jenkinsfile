// How the Ludwig platform ships.
//
// Three things are worth reading this file for, because they are decisions rather than plumbing:
//
//   1. Nothing here activates a Maven profile implicitly. Every profile is named on a command line
//      you can copy and run yourself. A build whose behaviour depends on whether env.JENKINS_URL
//      happens to be set cannot be reproduced on a laptop, which is exactly when you need to.
//
//   2. The image push is a separate, explicit goal. jib is configured in ludwig-service-parent but
//      bound to no lifecycle phase in the default build, so `mvn clean install` never touches a
//      registry. -Pci is what binds jib:build to `deploy`.
//
//   3. No credential appears in any POM. The registry password is a Jenkins credential, bound to
//      JIB_TARGET_USERNAME / JIB_TARGET_PASSWORD only for the stage that pushes.

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
    }

    environment {
        // -B batch mode (no ANSI, no progress spam), -ntp hides the download noise that otherwise
        // makes up most of a CI log.
        MAVEN_ARGS = '-B -ntp'
    }

    stages {
        stage('Build and test') {
            steps {
                // `install`, not `verify`, even though verify is the phase that runs failsafe, the
                // enforcer and the coverage gate - install runs all of those on its way past.
                //
                // The reason for install specifically: maven-checkstyle-plugin carries
                // ru.ludwigandreas:checkstyle-rules on its plugin classpath, and Maven resolves
                // plugin dependencies from the local repository rather than from the reactor. On an
                // agent with a cold ~/.m2, a bare `verify` would reach crud-service-example before
                // checkstyle-rules had been installed and fail to resolve it. `install` puts each
                // module in the local repository as it completes, so the later modules find it.
                sh 'mvn $MAVEN_ARGS clean install'
            }
            post {
                always {
                    junit testResults: '**/target/surefire-reports/*.xml,**/target/failsafe-reports/*.xml',
                          allowEmptyResults: true
                    recordCoverage(tools: [[parser: 'JACOCO', pattern: '**/target/site/jacoco*/jacoco.xml']])
                }
            }
        }

        stage('Publish artifacts') {
            when { branch 'master' }
            steps {
                // Deploys the jars and the flattened POMs to the repository named by
                // distributionManagement in the reactor root. Credentials come from a <server>
                // entry in the agent's settings.xml whose id matches ${ludwig.repo.snapshots.id}.
                //
                // -Pci also attaches sources jars, so what lands in the repository is debuggable.
                // This is the SNAPSHOT path: the version is whatever .mvn/maven.config says, and a
                // release overrides it - see the note at the bottom of this file.
                sh 'mvn $MAVEN_ARGS -Pci -DskipTests deploy'
            }
        }

        stage('Push image') {
            when { branch 'master' }
            steps {
                // The only stage that talks to a registry, and the only one that needs credentials.
                //
                // jib:build streams layers straight to the registry over HTTPS: this agent needs no
                // Docker daemon, no privileged container and no mounted daemon socket. A developer
                // wanting a local image runs `mvn jib:dockerBuild` instead.
                //
                // -pl limits this to the modules that actually produce an image. The libraries are
                // jars; asking jib to containerize them would be meaningless.
                withCredentials([usernamePassword(credentialsId: 'ludwig-registry',
                                                  usernameVariable: 'JIB_TARGET_USERNAME',
                                                  passwordVariable: 'JIB_TARGET_PASSWORD')]) {
                    sh '''
                        mvn $MAVEN_ARGS -Pci -DskipTests \
                            -pl crud-service-example,notification-service \
                            jib:build
                    '''
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}

// Releasing
// ---------
// A release is the same pipeline with a concrete version passed in:
//
//     mvn -B -ntp -Drevision=1.2.0 -Prelease -Pci deploy
//
// -Drevision overrides the SNAPSHOT value in .mvn/maven.config (a command-line user property beats
// one from maven.config), and flatten-maven-plugin writes that literal into every POM that is
// deployed. -Prelease adds two enforcer rules: the artifact itself must not be a SNAPSHOT, and it
// must not depend on one.
//
// The version is NOT set by the release profile. It cannot be: ${revision} arrives as a user
// property, and a user property beats a <properties> entry in any profile. Forgetting -Drevision
// therefore fails the build on requireReleaseVersion rather than silently deploying a SNAPSHOT
// under a release label.
