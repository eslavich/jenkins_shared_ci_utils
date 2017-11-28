// Jenkinsfile utilities
import BuildConfig.BuildConfig

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
                // System.exit(0) // WARNING: FATAL to Jenkins
                return skip_job
                //throw new hudson.AbortException('Guess what!')
                //throw new java.io.IOException('Guess what!')
            }
            sh(script: "ls -al")
            stash includes: '**/*', name: 'source_tree'
        }
    }
    return skip_job
}


def concurrent(configs) {
    def tasks = [:]
    println("Size of configs = ${configs.size()}")
    for (config in configs) {
        def myconfig = new BuildConfig()
        myconfig = config.copy()
        tasks["${config.nodetype}/${config.build_mode}"] = {
            node(config.nodetype) {
                //withEnv(config.env_vars) {
                    println("task: myconfig.nodetype = ${myconfig.nodetype}")
                    println("task: myconfig.build_mode = ${myconfig.build_mode}")
                    println("task: myconfig.build_args = ${myconfig.build_args}")
                    println("task: myconfig.env_vars = ${myconfig.env_vars}")
                    println("task: myconfig.run_tests = ${myconfig.run_tests}")
                    def prefix = pwd() + "/_install"
                    stage("Build (${myconfig.build_mode})") {
                        unstash "source_tree"
                        sh(script: "ls -al")
                    } //end stage
                    stage("Test (${myconfig.build_mode})") {
                        if (myconfig.run_tests) {
                            println("RUNNING TESTS")
                        }
                    }
                //} //end withEnv
            } // end node
        }
    } //end for
    stage("Matrix") {
        parallel(tasks)
    }
} //end concurrent

// Allow deep copying of a config object to another instance.
def copy(config) {
    return config.createNewInstance()
}
