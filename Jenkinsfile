pipeline{
	agent any
	options {
		buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '2'))
	}
	tools {
		jdk 'temurin-jdk17-latest'
	}
	environment {
		MAVEN_HOME = "$WORKSPACE/.m2/"
		MAVEN_USER_HOME = "$MAVEN_HOME"
	}
	stages{
		stage("Maven Build"){
			steps {
				sh './mvnw -B verify --file lemminx-maven/pom.xml -Pgenerate-vscode-jars -Dmaven.test.error.ignore=true -Dmaven.test.failure.ignore=true'
			}
			post {
				always {
					junit 'lemminx-maven/target/surefire-reports/TEST-*.xml'
					archiveArtifacts 'lemminx-maven/src/test/resources/build.log'
				}
			}
		}
		stage ('Deploy Maven artifacts') {
			when {
				branch 'master'
			}
			steps {
				sh './mvnw -B deploy  --file lemminx-maven/pom.xml -Pgenerate-vscode-jars  -DskipTests -Dcbi.jarsigner.skip=false'
			}
		}
	}
}
