/*******************************************************************************
 * Copyright (c) 2021-2026 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.completion;

import static org.eclipse.lemminx.XMLAssert.c;
import static org.eclipse.lemminx.XMLAssert.te;
import static org.eclipse.lemminx.XMLAssert.testCompletionFor;

import java.util.Arrays;

import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.InsertTextMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class MavenCompletionParticipantTest {
	
	// We must use this System.lineSeparator() as line delimiter only for those cases,
	// where a text editor line doesn't have a line ending separator set
	private static String LINE_DELIMTER = System.lineSeparator();
	
	@BeforeAll
	public static void setUp() {
		MavenLemminxExtension.setUnitTestMode(true);
	}

	@Test
	public void testOneExistingDependencyDependenciesCompletion() throws Exception {
		String pom = //
				"""
			<?xml version="1.0" encoding="UTF-8"?>
			<project
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
			                      https://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns="http://maven.apache.org/POM/4.0.0"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <artifactId>just-a-pom</artifactId>
			  <groupId>com.datho7561</groupId>
			  <version>0.1.0</version>
			  <dependencies>
			    <dependency>
			      <groupId>com.fasterxml.jackson.core</groupId>
			      <artifactId>jackson-core</artifactId>
			      <version>2.11.3</version>
			    </dependency>
			    maven-core|
			  </dependencies>
			</project>
			""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("maven-core - org.apache.maven:maven-core:3.2.5", //
				te(16, 4, 16, 14, //
						"""
							<dependency>
							      <groupId>org.apache.maven</groupId>
							      <artifactId>maven-core</artifactId>
							      <version>3.2.5</version>
							    </dependency>"""),
				"maven-core"));
	}

	@Test
	public void testOneExistingDependencyDependenciesCompletionAdjustIndentation() throws Exception {
		SharedSettings settings = new SharedSettings();
		CompletionCapabilities completionCapabilities = new CompletionCapabilities();
		settings.getCompletionSettings().setCapabilities(completionCapabilities);
		settings.getCompletionSettings().getCompletionCapabilities().setInsertTextMode(InsertTextMode.AdjustIndentation);
		String pom = //
				"""
			<?xml version="1.0" encoding="UTF-8"?>
			<project
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
			                      https://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns="http://maven.apache.org/POM/4.0.0"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <artifactId>just-a-pom</artifactId>
			  <groupId>com.datho7561</groupId>
			  <version>0.1.0</version>
			  <dependencies>
			    <dependency>
			      <groupId>com.fasterxml.jackson.core</groupId>
			      <artifactId>jackson-core</artifactId>
			      <version>2.11.3</version>
			    </dependency>
			    maven-core|
			  </dependencies>
			</project>
			""";
		testCompletionFor(new XMLLanguageService(), pom, null, null, "file:///pom.xml", null, settings, //
				c("maven-core - org.apache.maven:maven-core:3.2.5", //
				te(16, 4, 16, 14, //
						"""
							<dependency>
							  <groupId>org.apache.maven</groupId>
							  <artifactId>maven-core</artifactId>
							  <version>3.2.5</version>
							</dependency>"""),
				"maven-core"));
	}

	@Test
	public void testNoExistingDependencyDependenciesCompletion() throws Exception {
		String pom = """
			<?xml version="1.0" encoding="UTF-8"?>
			<project
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
			                      https://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns="http://maven.apache.org/POM/4.0.0"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <artifactId>just-a-pom</artifactId>
			  <groupId>com.datho7561</groupId>
			  <version>0.1.0</version>
			  <dependencies>
			    maven-core|
			  </dependencies>
			</project>
			""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("maven-core - org.apache.maven:maven-core:3.2.5", //
				te(11, 4, 11, 14, //
						"""
							<dependency>
							      <groupId>org.apache.maven</groupId>
							      <artifactId>maven-core</artifactId>
							      <version>3.2.5</version>
							    </dependency>"""),
				"maven-core"));
	}

	@Test
	public void testNoExistingDependencyDependencyCompletion() throws Exception {
		String pom = """
			<?xml version="1.0" encoding="UTF-8"?>
			<project
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
			                      https://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns="http://maven.apache.org/POM/4.0.0"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <artifactId>just-a-pom</artifactId>
			  <groupId>com.datho7561</groupId>
			  <version>0.1.0</version>
			  <dependencies>
			    <dependency>
			      maven-core|
			    </dependency>
			  </dependencies>
			</project>
			""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("maven-core - org.apache.maven:maven-core:3.2.5", //
				te(12, 6, 12, 16, //
						"""
							<groupId>org.apache.maven</groupId>
							      <artifactId>maven-core</artifactId>
							      <version>3.2.5</version>"""),
						"maven-core"));
	}

	@Test
	public void testNotPrefixedPropertyCompletion() throws Exception {
		String pom = """
			<?xml version="1.0" encoding="UTF-8"?>
			<project
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
			                      https://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns="http://maven.apache.org/POM/4.0.0"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <artifactId>just-a-pom</artifactId>
			  <groupId>com.datho7561</groupId>
			  <version>0.1.0</version>
			  <properties>
			    <spring.version>5.0.2</spring.version>
			  </properties>
			  <dependencies>
			    <dependency>
			      <groupId>org.springframework</groupId>
			      <artifactId>spring-context</artifactId>
			      <version>spring.ver|</version>
			      <scope>runtime</scope>
			    </dependency>
			  </dependencies>
			</project>
			""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("${spring.version}", //
				te(17, 25, 17, 25, //
						"${spring.version}"),
						"${spring.version}"));
	}

	@Test
	public void testInsertionWithAlreadyExistingGroupId() throws Exception {
		String pom = """
			<project xmlns="http://maven.apache.org/POM/4.0.0"
				xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
				xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
				<modelVersion>4.0.0</modelVersion>
				<groupId>org.test</groupId>
				<artifactId>test</artifactId>
				<version>0.0.1-SNAPSHOT</version>
				<dependencies>
					<dependency>
						<groupId>org.apache.maven.plugins</groupId>
						|
					</dependency>
				</dependencies>
			</project>""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("maven-surefire-plugin - org.apache.maven.plugins:maven-surefire-plugin:2.22.2", //
				te(10, 3, 10, 3, //
						"<artifactId>maven-surefire-plugin</artifactId>\n			<version>2.22.2</version>"),
						"maven-surefire-plugin"));
	}

	@Test
	public void testInsertionDependencyWithPartiallyTypedInArtifactId() throws Exception {
		String pom = """
			<project xmlns="http://maven.apache.org/POM/4.0.0"
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <groupId>org.test</groupId>
			  <artifactId>test</artifactId>
			  <version>0.0.1-SNAPSHOT</version>
			  <dependencies>
			    <dependency>
			      <artifactId>maven-surefire-plu|</artifactId>
			    </dependency>
			  </dependencies>
			</project>""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("maven-surefire-plugin - org.apache.maven.plugins:maven-surefire-plugin:2.22.2", //
					te(9, 18, 9, 36, //
							"maven-surefire-plugin"),
					Arrays.asList(
							te(8, 16, 8, 16, 
								"\n      <groupId>org.apache.maven.plugins</groupId>"),
							te(9, 49, 9, 49, 
								"\n      <version>2.22.2</version>")),
					"maven-surefire-plugin"));
	}

	@Test
	public void testInsertionDependencyWithPartiallyTypedInArtifactIdNotWellFormed() throws Exception {
		String pom = """
			<project xmlns="http://maven.apache.org/POM/4.0.0"
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <groupId>org.test</groupId>
			  <artifactId>test</artifactId>
			  <version>0.0.1-SNAPSHOT</version>
			  <dependencies>
			    <dependency>
			      <artifactId>maven-surefire-plu|</artifactId>
			""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
			c("maven-surefire-plugin - org.apache.maven.plugins:maven-surefire-plugin:2.22.2", //
					te(9, 18, 9, 36, //
							"maven-surefire-plugin"),
					Arrays.asList(
							te(8, 16, 8, 16, 
								"\n      <groupId>org.apache.maven.plugins</groupId>"),
							te(9, 49, 9, 49, 
								"\n      <version>2.22.2</version>")),
					"maven-surefire-plugin"));
	}

	@Test
	public void testInsertionDependencyWithPartiallyTypedInArtifactIdNotWellFormedNoEOL() throws Exception {
		String pom = """
			<project xmlns="http://maven.apache.org/POM/4.0.0"
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <groupId>org.test</groupId>
			  <artifactId>test</artifactId>
			  <version>0.0.1-SNAPSHOT</version>
			  <dependencies>
			    <dependency>
			      <artifactId>maven-surefire-plu|</artifactId>""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("maven-surefire-plugin - org.apache.maven.plugins:maven-surefire-plugin:2.22.2", //
					te(9, 18, 9, 36, //
						"maven-surefire-plugin"),
					Arrays.asList(
							te(8, 16, 8, 16, 
								LINE_DELIMTER + "      <groupId>org.apache.maven.plugins</groupId>"),
							te(9, 49, 9, 49, 
								LINE_DELIMTER + "      <version>2.22.2</version>")),
					"maven-surefire-plugin"));
	}

	@Test
	public void testInsertionDependencyWithPartiallyTypedInArtifactIdBrokenEnd() throws Exception {
		String pom = """
			<project xmlns="http://maven.apache.org/POM/4.0.0"
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <groupId>org.test</groupId>
			  <artifactId>test</artifactId>
			  <version>0.0.1-SNAPSHOT</version>
			  <dependencies>
			    <dependency>
			      <artifactId>maven-surefire-plu|""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("maven-surefire-plugin - org.apache.maven.plugins:maven-surefire-plugin:2.22.2", //
					te(9, 18, 9, 36, //
						"maven-surefire-plugin</artifactId>"),
					Arrays.asList(
							te(8, 16, 8, 16, 
								LINE_DELIMTER + "      <groupId>org.apache.maven.plugins</groupId>"),
							te(9, 36, 9, 36, 
									LINE_DELIMTER + "      <version>2.22.2</version>")),
					"maven-surefire-plugin"));
	}

	@Test
	public void testPluginParameterCompletion() throws Exception {
		String pom = """
			<project xmlns="http://maven.apache.org/POM/4.0.0"
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <groupId>org.test</groupId>
			  <artifactId>test</artifactId>
			  <version>0.0.1-SNAPSHOT</version>
			  <build>
			    <plugins>
			      <plugin>
				      <groupId>org.eclipse.tycho</groupId>
				      <artifactId>target-platform-configuration</artifactId>
				      <version>3.0.0</version>
				      <configuration>
				        |
				      </configuration>
			      </plugin>
			    </plugins>
			  </build>
			</project>""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("target", //
					te(14, 9, 14, 9, //
							"<target>$0</target>"),
					"<target>$0</target>", null));
	}
	
	@Test
	public void testPackagingyCompletion() throws Exception {
		String pom = """
			<?xml version="1.0" encoding="UTF-8"?>
			<project
			  xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
			                      https://maven.apache.org/xsd/maven-4.0.0.xsd"
			  xmlns="http://maven.apache.org/POM/4.0.0"
			  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
			  <modelVersion>4.0.0</modelVersion>
			  <artifactId>pom-with-packaging</artifactId>
			  <groupId>org.eclipse.lemminx.extention.maven.tests</groupId>
			  <version>0.1.0</version>
			  <packaging>|</packaging>
			  <build>
			      <plugins>
			        <plugin>
			          <groupId>org.eclipse.tycho</groupId>
			          <artifactId>tycho-maven-plugin</artifactId>
			          <version>2.7.5</version> <!-- Later versions may not work here -->
			          <extensions>true</extensions>
			        </plugin>
			      </plugins>
			  </build>
			</project>
			""";
		testCompletionFor(pom, null, "file:///pom.xml", null, //
				c("jar", te(10, 13, 10, 13, "jar"), "jar"),
				c("war", te(10, 13, 10, 13, "war"), "war"),
				c("ear", te(10, 13, 10, 13, "ear"), "ear"),
				c("ejb", te(10, 13, 10, 13, "ejb"), "ejb"),
				c("pom", te(10, 13, 10, 13, "pom"), "pom"),
				c("maven-plugin", te(10, 13, 10, 13, "maven-plugin"), "maven-plugin"),
				c("eclipse-plugin", te(10, 13, 10, 13, "eclipse-plugin"), "eclipse-plugin"));
	}
}