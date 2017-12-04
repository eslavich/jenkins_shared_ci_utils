// Jenkinsfile support utilities
import BuildConfig.BuildConfig
import org.apache.commons.lang3.SerializationUtils

// Clone the source repository and examine the most recent commit message.
// If a '[ci skip]' or '[skip ci]' directive is present, immediately
// terminate the job with a success code.
// If no skip directive is found, stash all the source files for efficient retrieval
// by subsequent nodes.
def scm_checkout() {
    skip_job = 0
    node("on-master") {
        stage("Setup") {
            checkout(scm)
            // Obtain the last commit message and examine it for skip directives.
            logoutput = sh(script:"git log -1 --pretty=%B", returnStdout: true).trim()
            if (logoutput.contains("[ci skip]") || logoutput.contains("[skip ci]")) {
                skip_job = 1
                currentBuild.result = 'SUCCESS'
                println("\nBuild skipped due to commit message directive.\n")
                return skip_job
            }
            stash includes: '**/*', name: 'source_tree'
        }
    }
    return skip_job
}


// Execute build/test tasks in parallel
// Each task is defined by a BuildConfig object.
// A list of such objects is iterated over to process all configurations.
def concurrent(configs) {
    def tasks = [:]
    for (config in configs) {
        def myconfig = new BuildConfig() // MUST be inside for loop.
        myconfig = SerializationUtils.clone(config)

        // Code defined within 'tasks' is eventually executed on a separate node.
        tasks["${config.nodetype}/${config.build_mode}"] = {
            node(config.nodetype) {
                def runtime = []
                // Expand environment variable specifications by using the shell
                // to dereference any var references and then render the entire
                // value as a canonical path.
                for (var in myconfig.env_vars) {
                    def varName = var.tokenize("=")[0]
                    def varValue = var.tokenize("=")[1]
                    // examine var value, if it contains var refs, expand them.
                    def expansion = varValue
                    if (varValue.contains("\$")) {
                        expansion = sh(script: "echo ${varValue}", returnStdout: true)
                    }
                    // Convert var value to canonical based on a WORKSPACE base directory.
                    canonicalVarValue = new File(env.WORKSPACE, expansion).getCanonicalPath().trim()
                    runtime.add("${varName}=${canonicalVarValue}")
                }
                withEnv(runtime) {
                    stage("Build (${myconfig.build_mode})") {
                        unstash "source_tree"
                        for (cmd in myconfig.build_cmds) {
                            sh(script: cmd)
                        }
                    }
                    if (myconfig.test_cmds.size() > 0) {
                        try {
                            stage("Test (${myconfig.build_mode})") {
                                for (cmd in myconfig.test_cmds) {
                                    sh(script: cmd)
                                }
                            }
                        }
                        finally {
                            // TODO: Test for presence of report file.
                            step([$class: 'XUnitBuilder',
                                thresholds: [
                                [$class: 'SkippedThreshold', unstableThreshold: "${myconfig.skippedUnstableThresh}"],
                                [$class: 'SkippedThreshold', failureThreshold: "${myconfig.skippedFailureThresh}"],
                                [$class: 'FailedThreshold', unstableThreshold: "${myconfig.failedUnstableThresh}"],
                                [$class: 'FailedThreshold', failureThreshold: "${myconfig.failedFailureThresh}"]],
                                tools: [[$class: 'JUnitType', pattern: '*.xml']]])
                        }
                    }
                } // end withEnv
            } // end node
        } //end tasks

    } //end for
    stage("Matrix") {
        parallel(tasks)
    }
} //end concurrent


// Convenience function that performs a deep copy on the supplied object.
def copy(obj) {
    return SerializationUtils.clone(obj)
}
