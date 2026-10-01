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
//
//   4. The version is computed, not typed. GitVersion reads the git history and the pipeline
//      passes the answer in as -Drevision on every single Maven line. .mvn/maven.config is the
//      fallback for a checkout with no tags and for a laptop with no GitVersion; nothing here
//      edits it, and an enforcer rule fails the build if it ever holds a non-SNAPSHOT.

pipeline {
    agent any

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '30'))
        disableConcurrentBuilds()
        // The default checkout is shallow and drops tags on many agents, and GitVersion cannot
        // compute anything from a truncated history. The Checkout stage below does it explicitly
        // instead, so what the tool sees is a property of this file rather than of the agent.
        skipDefaultCheckout(true)
    }

    environment {
        // -B batch mode (no ANSI, no progress spam), -ntp hides the download noise that otherwise
        // makes up most of a CI log.
        MAVEN_ARGS = '-B -ntp'

        // Pinned by name, version AND digest, like every other image reference in this
        // repository - scripts/check_image_pins.sh fails the build on a bare tag, and a build
        // tool gets no exception from the container-image-pinning capability. A tag can be
        // re-pointed, and an image that computes the version of everything this platform
        // publishes is the last place to accept that.
        //
        // Running GitVersion in a container is what keeps .NET off the agent entirely.
        GITVERSION_IMAGE = 'gittools/gitversion:6.8.2-alpine.3.23-9.0@sha256:83c13bd14e1fe58c7dbfe03e7236cbc09f6e0a7078957dbaad02367cfad3784f'
    }

    stages {
        stage('Checkout') {
            steps {
                // shallow: false and noTags: false are the whole point of this stage. GitVersion
                // walks back to the last tag; with a depth-1 clone it either fails or, worse,
                // computes a plausible version from a history that is not the history.
                checkout([$class                           : 'GitSCM',
                          branches                         : scm.branches,
                          userRemoteConfigs                : scm.userRemoteConfigs,
                          doGenerateSubmoduleConfigurations: false,
                          extensions                       : [
                                  [$class: 'CloneOption', shallow: false, noTags: false, depth: 0, honorRefspec: true],
                                  [$class: 'LocalBranch', localBranch: '**']
                          ]])
                // Belt and braces: a cached workspace can carry an old tag set, and a tag that is
                // missing reads to GitVersion exactly like a release that never happened.
                sh 'git fetch --tags --force --prune'
            }
        }

        stage('Version') {
            steps {
                script {
                    // /nofetch: the checkout above already has the history, and GitVersion
                    // fetching on its own inside the container would need credentials it must
                    // never be given.
                    def majorMinorPatch = sh(
                            returnStdout: true,
                            script: """
                                docker run --rm -v "\$(pwd):/repo" ${GITVERSION_IMAGE} \
                                    /repo /nofetch /showvariable MajorMinorPatch
                            """).trim()

                    if (!(majorMinorPatch ==~ /^\d+\.\d+\.\d+$/)) {
                        error("GitVersion returned '${majorMinorPatch}', which is not an x.y.z. " +
                              'Refusing to build: a version nobody can parse is worse than no build.')
                    }

                    // THE SUFFIX IS DECIDED HERE, NOT BY GITVERSION. A GitVersion pre-release
                    // label such as 1.3.0-alpha.4 is valid SemVer and is NOT a Maven snapshot:
                    // Maven would publish it to the releases repository as an immutable artifact
                    // and it would satisfy requireReleaseDeps in a consumer, while being an
                    // untested branch build. Maven's two-repository split is the constraint, so
                    // the only two shapes this pipeline ever produces are x.y.z and
                    // x.y.z-SNAPSHOT.
                    def tagged = sh(returnStatus: true,
                                    script: 'git describe --exact-match --tags HEAD') == 0

                    env.LUDWIG_REVISION = tagged ? majorMinorPatch : "${majorMinorPatch}-SNAPSHOT"
                    env.LUDWIG_IS_RELEASE = tagged ? 'true' : 'false'

                    echo "Version: ${env.LUDWIG_REVISION} (HEAD ${tagged ? 'is' : 'is not'} tagged)"
                    currentBuild.displayName = "${env.LUDWIG_REVISION} #${env.BUILD_NUMBER}"
                }
            }
        }

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
                sh 'mvn $MAVEN_ARGS -Drevision=$LUDWIG_REVISION clean install'
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
                // -Prelease only on a tagged commit. It ENFORCES the release preconditions -
                // a concrete version, no SNAPSHOT dependencies, a changelog heading in both
                // locales - and cannot set the version; the version came from the tag by way of
                // -Drevision. On an untagged commit the revision is a SNAPSHOT and Maven routes
                // it to the snapshots repository on its own.
                script {
                    def releaseProfile = env.LUDWIG_IS_RELEASE == 'true' ? '-Prelease ' : ''
                    sh "mvn \$MAVEN_ARGS -Drevision=\$LUDWIG_REVISION ${releaseProfile}-Pci -DskipTests deploy"
                }
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
                //
                // Selected by :artifactId rather than by directory. A module's path is a layout
                // decision and has already changed once (services moved under services/); its
                // artifactId is a published coordinate and does not move.
                withCredentials([usernamePassword(credentialsId: 'ludwig-registry',
                                                  usernameVariable: 'JIB_TARGET_USERNAME',
                                                  passwordVariable: 'JIB_TARGET_PASSWORD')]) {
                    sh '''
                        mvn $MAVEN_ARGS -Drevision=$LUDWIG_REVISION -Pci -DskipTests \
                            -pl :crud-service-example,:notification-service \
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
// A RELEASE IS A TAG. Nothing else. Push `v1.2.0` onto master and this same pipeline does the
// rest: GitVersion resolves the tagged commit to 1.2.0, `git describe --exact-match` sees the
// tag, the Version stage drops the -SNAPSHOT suffix and adds -Prelease, and the Publish stage
// deploys to the releases repository instead of the snapshots one.
//
// What a person does:
//
//     git tag -a v1.2.0 -m 'Release 1.2.0' && git push origin v1.2.0
//
// What nobody does any more: edit .mvn/maven.config. That file holds the tag-less fallback for a
// local build and nothing else, an enforcer rule fails the build if it ever stops being a
// SNAPSHOT, and a value in it that disagrees with the history is a trap for the next person who
// runs `mvn install` on a laptop.
//
// Reproducing a release build by hand, which is the point of keeping every profile explicit:
//
//     mvn -B -ntp -Drevision=1.2.0 -Prelease -Pci deploy
//
// -Drevision arrives as a user property and beats the value in .mvn/maven.config;
// flatten-maven-plugin writes that literal into every POM that is deployed. -Prelease only
// ENFORCES - the artifact must not be a SNAPSHOT, it must not depend on one, and both CHANGELOG.md
// and CHANGELOG.ru.md must carry a heading for the version. It cannot SET the version: ${revision}
// is a user property, and a user property beats a <properties> entry in any profile. Forgetting
// -Drevision therefore fails on requireReleaseVersion rather than silently deploying a SNAPSHOT
// under a release label.
//
// The first release, v1.1.0, is also the API compatibility baseline: from the release after it,
// revapi compares every published library against what 1.1.0 promised and fails a build whose
// version increment is too small for the difference it contains.
