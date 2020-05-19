#!/usr/bin/groovy 

def call(String type, String branch_name, String commit_hash, String result) {   
    def message = "${type}: ${BUILD_TIMESTAMP}:\n ${env.JOB_NAME}/${env.BUILD_NUMBER}/${branch_name}/${commit_hash}::"   
    if ( result == "SUCCESS" ) {
        slackSend color: "good", message: "${message} successful"   
    }
    else if( result == "FAILURE" ) {
        slackSend color: "danger", message: "${message} failed"   
    }
    else if( result == "UNSTABLE" ) {
        slackSend color: "warning", message: "${message} unstable"
    }
    else {
        slackSend color: "danger", message: "${message} unclear"   
    } 
}

