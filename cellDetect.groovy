pipeline {
    agent none
       environment {
            Exception_ = ''
        }
   
    stages {
        stage('CURL Request') {
            agent {label 'cellDetect_curl_dev'}
            steps {
                script {
                    try{
                        def jsonData = sh(script: 'curl -s http://apollo2.humanbrain.in:8000/analytics/getcdAnalyticsJob/CellDetectionTile', returnStdout: true).trim()
            
                        if (jsonData) {
                            def buildId = env.BUILD_ID
                            def sectionNo = sh(script: """echo '${jsonData}' | grep -o '"sectionNo":[0-9]*' | grep -o '[0-9]*'""", returnStdout: true).trim()
                            def jp2Path = sh(script: """echo '${jsonData}' | grep -o '"jp2Path":"[^"]*"' | cut -d':' -f2 | tr -d '\"'""", returnStdout: true).trim()
                            def fileName = sh(script: """echo '${jsonData}' | grep -o '"fileName":"[^"]*"' | cut -d':' -f2 | tr -d '\"'""", returnStdout: true).trim()
                            def biosample = sh(script: """echo '${jsonData}' | grep -o '"biosample":[0-9]*' | grep -o '[0-9]*'""", returnStdout: true).trim()
                            def jobid = sh(script: """echo '${jsonData}' | grep -o '"jobid":[0-9]*' | grep -o '[0-9]*'""", returnStdout: true).trim()
                            def datFileName = fileName.replaceAll(".jp2", ".dat")
                            def pklFileName = fileName.replaceAll(".jp2", "_info.pkl")
                            def pngFileName = fileName.replaceAll(".jp2", ".png")
                            def SeriesType = sh(script: "echo '${jp2Path}' | awk -F'/' '{print \$(NF-1)}'", returnStdout: true).trim()
                            def sectionId = sh(script: """echo '${jsonData}' | grep -o '"secId":[0-9]*' | grep -o '[0-9]*'""", returnStdout: true).trim()
                            def seriesId = sh(script: """echo '${jsonData}' | grep -o '"seriesId":[0-9]*' | grep -o '[0-9]*'""", returnStdout: true).trim()
                            
                            echo "Current Build ID: ${buildId}"
                            echo "sectionNo: ${sectionNo}"
                            echo "seriesId: ${seriesId}"
                            echo "jp2Path: ${jp2Path}"
                            echo "fileName: ${fileName}"
                            echo "biosample: ${biosample}"
                            echo "jobid: ${jobid}"
                            echo "datFileName: ${datFileName}"
                            echo "pklFileName: ${pklFileName}"
                            echo "pngFileName: ${pngFileName}"
                            echo "SeriesType: ${SeriesType}"
                            echo "SectionId: ${sectionId}"
                            
                            // Setting jp2Path as an environment variable for subsequent stages
                            env.SECTION_NO=sectionNo
                            env.SERIES_ID=seriesId
                            env.BIOSAMPLE=biosample
                            env.JP2_PATH = jp2Path
                            env.FILE_NAME = fileName
                            env.DAT_FILE_NAME = datFileName
                            env.PKL_FILE_NAME = pklFileName
                            env.PNG_FILE_NAME = pngFileName
                            env.JOB_ID = jobid
                            env.SERIES_TYPE = SeriesType
                            env.SECTIONID = sectionId
                            env.reason = ''
                            env.worker='cellDetect_worker_tile'
                            
                            // Assuming you have captured job_id and build_id into their respective environment variables
                            def apiUrl = "http://apollo2.humanbrain.in:8000/processing/CellDetectionJenkinsJobViewSet/"
                            def response = sh(script: """curl -X POST -u admin:admin -d 'jenkins_build=${env.BUILD_ID}&job_id=${env.JOB_ID}&reason=${env.reason}&worker=${env.worker}' ${apiUrl}""", returnStatus: true)
    
                            def baseDir = sh(script: "dirname '${env.JP2_PATH}'", returnStdout: true).trim()
                            env.BASE_DIR = baseDir
                            echo "baseDir: ${baseDir}"
                        } else {
                            echo "No data returned from the curl request."
                        }
                        sh 'echo "Exit code: $?"'
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        Exception_ = "Error in CURL Request stage: ${e.message}"
                        error(Exception_)
                    }
                }
            }
        }

        stage('copyLossless') {
            agent {label 'cellDetect_decode'}
            steps{
                sh 'rsync -Pav appUser@apollo1.humanbrain.in:${JP2_PATH} /store/cellDetect/input'
            }
        }
        stage('decode') {
           
            agent {
                docker {
                    image 'hbp1/hbpbase:1.1'
                    args '-v /store/cellDetect/input:/input -v /store/cellDetect/output:/output'
                    label 'cellDetect_decode'
                }
            }
            steps {
                pwd()
                script {
                    def credentials = ['dgx2_ssh','pp1_ssh', 'pp2_ssh', 'pp4_ssh','pp5_ssh'] 

                    for (cred in credentials) {
                        try {
                            echo "Trying with credential ID: ${cred}"
                            git credentialsId: cred, url: 'git@bitbucket.org:hbp_iitm/image_computing_base.git'
                            // If git command is successful, break the loop
                            break
                        } catch (Exception e) {
                            echo "Failed with credential ID: ${cred}"
                        }
                    }
                    try {
                        sh 'python3 ./create_mmap_script.py /input/${FILE_NAME} /output/special/jp2cache'
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        Exception_ = "Error in decode stage: ${e.message}"
                        error(Exception_)
                    }
                }
            }
        }
        stage('getMask') {
           
            agent {
                docker {
                    image 'hbp1/hbpbase:1.1'
                    args ' -v /store/repos2/iitlab/humanbrain/analytics/${BIOSAMPLE}/${SERIES_TYPE}/mask:/mask'
                    label 'cellDetect_decode'
                }
            }
            steps {
                pwd()
                script {
                    def credentials = ['dgx2_ssh','pp1_ssh', 'pp2_ssh', 'pp4_ssh','pp5_ssh'] 

                    for (cred in credentials) {
                        try {
                            echo "Trying with credential ID: ${cred}"
                            git credentialsId: cred, url: 'git@bitbucket.org:hbp_iitm/image_computing_base.git'
                            // If git command is successful, break the loop
                            break
                        } catch (Exception e) {
                            echo "Failed with credential ID: ${cred}"
                        }
                    }
                    try {
                        sh "python3 ./get_mask_tilegiven.py ${SECTIONID} ${PNG_FILE_NAME} ${SERIES_ID} ${SECTION_NO}"
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        Exception_ = "Error in decode stage: ${e.message}"
                        error(Exception_)
                    }
                }
            }
        }
        stage('celldetect'){
            agent {label 'cellDetect'}
            steps {
                script{
                    try{
                        sh """
                            export BATCHSIZE=\"${params.BATCHSIZE}\"
                            export CHUNK_SHAPE=\"${params.CHUNK_SHAPE}\"
                        """
                        sh '''
                            echo $CUDA_VISIBLE_DEVICES
                            docker run --rm --gpus=all -e CUDA_VISIBLE_DEVICES --ipc=host -v /store/cellDetect:/data -v /store/repos2/iitlab/humanbrain/analytics/:/output -v /store/cellDetect/cache:/cache hbp1/app_celldet_hovernet_mmap:1.0 /workspace/step2.sh ${BIOSAMPLE} ${SECTION_NO} /data/output/special/jp2cache /output/${BIOSAMPLE}/${SERIES_TYPE} /cache 0 32 $BATCHSIZE $CHUNK_SHAPE 0
                        '''
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        def Exception_ = "Error in celldetect stage: ${e.message}"
                        error(Exception_)
                    }
                }
            }
        }
        stage('updateDB') {
           
            agent {
                docker {
                    image 'hbp1/hbpbase:1.1'
                    args '-v /store/repos1:/store/repos1 -v /store/nvmestorage/keerthi/data:/output'
                    label 'postgisUpdate'
                }
            }
            environment {
                DT = sh(script: 'date +%y%h%d_%H%M', returnStdout: true).trim()
                ALGO='hover_net'
                VER='v1'
            }
            steps {
                script {
                    try {
                        pwd()
                        echo "$DT"
                        git credentialsId: 'ap3_ssh', url: 'git@bitbucket.org:hbp_iitm/image_computing_base.git'
                        sh 'python3 ./setup_db.py  ${BIOSAMPLE} ${SECTION_NO} $ALGO $VER ${FILE_NAME} $DT'
                        sh 'python3 ./insert_db_records.py ${BIOSAMPLE} ${SECTION_NO} ${BASE_DIR} ${FILE_NAME} $DT'
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        Exception_ = "Error in updateDB stage: ${e.message}"
                        error(Exception_)
                    }
                }
            }
        }
        stage('GetMetrics') {
           
            agent {
                docker {
                    image 'hbp1/hbpbase:1.1'
                    label 'cellDetect_decode'
                }
            }
            steps {
                pwd()
                script {
                    def credentials = ['dgx2_ssh','pp1_ssh', 'pp2_ssh', 'pp4_ssh','pp5_ssh'] 

                    for (cred in credentials) {
                        try {
                            echo "Trying with credential ID: ${cred}"
                            git credentialsId: cred, url: 'git@bitbucket.org:hbp_iitm/image_computing_base.git'
                            break
                        } catch (Exception e) {
                            echo "Failed with credential ID: ${cred}"
                        }
                    }
                    try {
                        sh 'python3 ./calc_metrics.py ${BIOSAMPLE} ${SECTIONID} ${SECTION_NO}'
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        Exception_ = "Error in GetMetrics stage: ${e.message}"
                        error(Exception_)
                    }
                }
            }
        }
        stage('removefiles') {
            agent {label 'cellDetect_decode'}
            steps{
                sh 'rm  /store/cellDetect/output/special/jp2cache/${DAT_FILE_NAME}'
                sh 'rm  /store/cellDetect/output/special/jp2cache/${PKL_FILE_NAME}'
                sh 'rm  /store/cellDetect/input/${FILE_NAME}'
            }
        }
        
    }
    post {
        success {
            node('cellDetect_curl_dev') {
                script {
                    def build_status = 2
                    def apiUrl = "http://apollo2.humanbrain.in:8000/processing/CellDetectionSeriesJob/${env.JOB_ID}/"
                    def response = sh(script: """curl -X PATCH -u admin:admin -d 'process_status=${build_status}' ${apiUrl}""", returnStatus: true)
                }
            }
        }
        failure {
            node('cellDetect_curl_dev') {
                script {
                    def build_status = 3
                    def apiUrl = "http://apollo2.humanbrain.in:8000/processing/CellDetectionSeriesJob/${env.JOB_ID}/"
                    def response = sh(script: """curl -X PATCH -u admin:admin -d 'process_status=${build_status}' ${apiUrl}""", returnStatus: true)
                    def apiUrl1 = "http://apollo2.humanbrain.in:8000/processing/patchCdJenkins"
                    def response1 = sh(script: """curl -X PATCH -u admin:admin -d 'jenkins_build=${env.BUILD_ID}&job_id=${env.JOB_ID}&reason=${Exception_}&worker=${env.worker}' ${apiUrl1}""", returnStatus: true)
    
                }
            }
            
            node('cellDetect_decode') {
                sh 'rm  /store/cellDetect/input/${FILE_NAME}'
                sh 'rm  /store/cellDetect/output/special/jp2cache/${DAT_FILE_NAME}'
                sh 'rm  /store/cellDetect/output/special/jp2cache/${PKL_FILE_NAME}'
            }
        }
    }

}
