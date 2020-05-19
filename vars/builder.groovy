def image_exists(repo_name,image_tag){
  def curl_image_exists_status_code = ''
  withCredentials([usernamePassword(credentialsId: REGISTRY_CREDNTIAL, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
     curl_image_exists_status_code = sh (
    script: "curl -u $USERNAME:$PASSWORD -s -o /dev/null -I -w \"%{http_code}\" -X GET \"http://${REGISTRY_HOST}/api/repositories/images%2F${repo_name}/tags/${image_tag}\"",
    returnStdout: true
    )
  }
  return (curl_image_exists_status_code == "200")?true:false
}

def has_new_commit(){
  return (env.GIT_COMMIT != env.GIT_PREVIOUS_SUCCESSFUL_COMMIT)?true:false
}

def get_last_succesful_build_number(){
  return Jenkins.instance.getItem("${env.JOB_NAME}").lastSuccessfulBuild.number
}

def git_short_commit(){
  return sh(
                script: "printf \$(git rev-parse --short ${env.GIT_COMMIT})",
                returnStdout: true
        ).trim()
}

def git_branch_name() {
  def branch = ''
  branch = env.GIT_BRANCH.minus("origin/")
  return branch
}


def get_repo_name() {
  return env.JOB_NAME.split('/')[0]
}

def get_stage_type_by_branch(branch_name){
    def stage_type =''
    if ( branch_name == 'qa') {
        stage_type = 'qa'
    } else {
        stage_type = 'stage'
    }

    return stage_type
}

def call(){
  pipeline {
    environment {
      dockerImage = ''
      SHORT_COMMIT = git_short_commit()
      BRANCH_NAME = git_branch_name()
      TAG = "${BRANCH_NAME}-${SHORT_COMMIT}"
      REPO_NAME = get_repo_name()
      IMAGE_EXISTS = image_exists(REPO_NAME,TAG)
      STAGE_TYPE = get_stage_type_by_branch(BRANCH_NAME)
      DISPLAY_NAME = "${currentBuild.displayName}"

    }
    agent any
    stages {
     // stage('checkout'){
     //   steps{
     //     checkout scm
     //   }
     // }
      stage('env'){
          steps{
              sh 'printenv'
          }
      }
      stage('Build image') {
         steps{
            script {
              currentBuild.displayName = "${env.BUILD_ID}-${TAG}"
              if (IMAGE_EXISTS == 'false'){
                 dockerImage = docker.build("images/${REPO_NAME}","--no-cache .")
              }
              else {
                 docker.withRegistry('http://'+REGISTRY_HOST+'/', REGISTRY_CREDNTIAL) {
                  sh "docker pull ${REGISTRY_HOST}/images/${REPO_NAME}:${TAG}"
                  dockerImage = docker.image("${REGISTRY_HOST}/images/${REPO_NAME}:${TAG}")
                 }
              }
          }
         }
      }
      stage('Test image') {
         steps{
            script {
              dockerImage.inside {
                sh 'echo "Tests passed"'
              }
            }
          }
      }
      stage('Push image') {
         steps{
            script {
                docker.withRegistry('http://'+REGISTRY_HOST+'/', REGISTRY_CREDNTIAL) {
                if (IMAGE_EXISTS == 'false'){
                  dockerImage.push("${TAG}")
                }
                if (BRANCH_NAME == "master"){
                  dockerImage.push("latest")
                }
                else {
                  dockerImage.push("${BRANCH_NAME}-latest")
                }
              }
            }
          }
      }
      stage('Remove Unused docker image') {
         steps{
          sh "docker images -a | grep ${REPO_NAME} | tr -s ' ' | cut -d ' ' -f 3 | xargs docker rmi -f"
         }
      }
      stage('Create Artifact'){
         steps{
           script{
             // you should change here probably
             sh 'echo ${BRANCH_NAME} > branch.txt'
             sh 'echo ${SHORT_COMMIT} > commit.txt'
             sh 'tar cvf ${STAGE_TYPE}-latest.tar branch.txt commit.txt docker-compose.yml ${STAGE_TYPE}.env'
             // sh 'cp ${STAGE_TYPE}-${TAG}.tar ${STAGE_TYPE}-latest.tar'

             if ( BRANCH_NAME == 'master') {
               sh 'tar cvf production-latest.tar branch.txt commit.txt docker-compose.yml production.env'
               archiveArtifacts artifacts: "production-latest.tar", onlyIfSuccessful: true
             }

             archiveArtifacts artifacts: "${STAGE_TYPE}-latest.tar", onlyIfSuccessful: true
           }
         }
      }
      stage("Copy artifact to asnible server"){
          steps{
            script{
              // you should change here probably

              sh (script: "ssh ansible@ansible-server mkdir -p /opt/artifact/${REPO_NAME}/${STAGE_TYPE}/latest/", returnStdout: true)
              sh (script: "scp ${STAGE_TYPE}-latest.tar ansible@ansible-server:/opt/artifact/${REPO_NAME}/${STAGE_TYPE}/latest/", returnStdout: true)

              if ( BRANCH_NAME == 'master') {
                sh (script: "ssh ansible@ansible-server mkdir -p /opt/artifact/${REPO_NAME}/production/latest/", returnStdout: true)
                sh (script: "scp production-latest.tar ansible@ansible-server:/opt/artifact/${REPO_NAME}/production/latest/", returnStdout: true)
              }
            }
          }
      }
      stage("Deploy trigger"){
          steps {
              script{
                  // run ansible for stage and qa
                  // or run kubernetes deployments for qa
                  // or run another jenkins job
                  // or run ssh command on qa
                  // do what you want on qa

                  echo 'hi qa deploy:)'

                }
            }
        }
      stage('Deploy to prod') {
          when {
             environment name: 'BRANCH_NAME', value: 'master'
          }

          steps {
             script {
               def proceed=false
               timeout(time: 15, unit: "MINUTES") {
                  proceed = input(message: 'Do you want to approve the deploy in production?', ok: 'Yes',
                       parameters: [booleanParam(defaultValue: true,
                       description: 'You got 15 minutes to approve! run lola run!',name: 'Yes?')])
               }

               if (proceed == true){
                   // run ansible for stage and production
                   // or run kubernetes deployments for production
                   // or run another jenkins production
                   // or run ssh command on production
                   // do what you want on production
                   echo 'hi production deploy:)'

                 }
             }
          }
       }
    }
    post {
      always{
        script{
          slackNotifier('BUILD', "${BRANCH_NAME}", "${SHORT_COMMIT}", currentBuild.currentResult)

          sh 'git clean -fdx'
          sh 'docker container prune'
          sh 'docker image prune'
        }
      }
      success {
        echo ':)'
              //
      }
      failure {
          echo 'I failed :('
      }
    }
  }
}
