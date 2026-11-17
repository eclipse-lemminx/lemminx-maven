/*******************************************************************************
 * Copyright (c) 2021-2026 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven;

import static org.eclipse.lemminx.extensions.maven.utils.LocalRepositoryUtils.MAVEN_LOCAL_REPO_PROPERTY_NAME;
import static org.eclipse.lemminx.extensions.maven.utils.LocalRepositoryUtils.getLocalRepositoryPaths;
import static org.eclipse.lemminx.extensions.maven.utils.LocalRepositoryUtils.getTempLocalRepositoryPath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenExecutionRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class InitMavenRequestTest {

	private static final String TEST_LOCAL_REPO_PATH = "/.m2/bug383repository";
	
	private static final String TEST_DUMMY_PROPERTY_NAME = "DUMMY_USER_HOME";
	private static final String HOSTNAME_ENV_VARIABLE_NAME = isWindows() ? "COMPUTERNAME" : "HOSTNAME";
	private static final String MAVEN_PROJECTBASEDIR_ENV_VARIABLE_NAME = "MAVEN_PROJECTBASEDIR";
	private static final String HOME_ENV_VARIABLE_NAME =  isWindows() ?  "HOMEPATH" : "HOME";
	
	@BeforeAll
	public static void setUp() {
		MavenLemminxExtension.setUnitTestMode(true);
	}
	
	@Test
	public void testUserSettingsWithSystemProperty() throws Exception {
		final File localSettingsFile = generateSettingsXml(TEST_DUMMY_PROPERTY_NAME);
		String localRepoProperty = System.getProperty(MAVEN_LOCAL_REPO_PROPERTY_NAME);
		Properties properties = System.getProperties();
		try {
			properties.remove(MAVEN_LOCAL_REPO_PROPERTY_NAME); // Not to use local repository set from properties
			
			// Add a DUMMY environment variable to check later if it's really resolved to a real path
			// Avoiding any ${HOME} and ${user.home} variable/properties values as they are 
			// used as defaults in maven
			String hostname = "/" + System.getenv(HOSTNAME_ENV_VARIABLE_NAME);
			properties.put(TEST_DUMMY_PROPERTY_NAME, hostname);
			System.setProperties(properties);
			
			MavenLemminxExtension plugin = new MavenLemminxExtension();
			plugin.settings.setGlobalSettings(null);
			plugin.settings.setUserSettings(localSettingsFile.getAbsolutePath());
			
			MavenExecutionRequest mavenRequest = plugin.getMavenSession().getRequest();			
			File expectedLocalRepoPath = new File(hostname, TEST_LOCAL_REPO_PATH);
			
			// Test with temporary lemminx-maven local repository
			File expectedTempLocalRepoPath = getTempLocalRepositoryPath(expectedLocalRepoPath);
			File actualTempLocalRepoPath = mavenRequest.getLocalRepositoryPath();
			assertNotNull(actualTempLocalRepoPath);
			assertEquals(expectedTempLocalRepoPath.getAbsolutePath(), actualTempLocalRepoPath.getAbsolutePath());
			
			// Test with the real local repository
			File actualLocalRepoPath = getLocalRepositoryPaths(mavenRequest).get(0);
			assertNotNull(actualLocalRepoPath);
			assertEquals(expectedLocalRepoPath.getAbsolutePath(), actualLocalRepoPath.getAbsolutePath());
			
		} finally {
			if (localSettingsFile != null) {
				localSettingsFile.delete();
			}
			
			// Restore original System properties
			properties.remove(TEST_DUMMY_PROPERTY_NAME);
			if (localRepoProperty != null) {
				properties.put(MAVEN_LOCAL_REPO_PROPERTY_NAME, localRepoProperty);
			}
			System.setProperties(properties);
		}
	}
	
	@Test
	public void testUserSettingsWithEnvVariables() throws Exception {
		final File localSettingsFile = generateSettingsXml(MAVEN_PROJECTBASEDIR_ENV_VARIABLE_NAME);
		String localRepoProperty = System.getProperty(MAVEN_LOCAL_REPO_PROPERTY_NAME);
		Properties properties = System.getProperties();
		try {
			properties.remove(MAVEN_LOCAL_REPO_PROPERTY_NAME); // Not to use local repository set from properties
			System.setProperties(properties);

			String mavenProjectBasedirVariable = System.getenv(MAVEN_PROJECTBASEDIR_ENV_VARIABLE_NAME);
			
			MavenLemminxExtension plugin = new MavenLemminxExtension();
			plugin.settings.setGlobalSettings(null);
			plugin.settings.setUserSettings(localSettingsFile.getAbsolutePath());
			
			MavenExecutionRequest mavenRequest = plugin.getMavenSession().getRequest();			
			File expectedLocalRepoPath = new File(mavenProjectBasedirVariable, TEST_LOCAL_REPO_PATH);
			
			// Test with temporary lemminx-maven local repository
			File expectedTempLocalRepoPath = getTempLocalRepositoryPath(expectedLocalRepoPath);
			File actualTempLocalRepoPath = mavenRequest.getLocalRepositoryPath();
			assertNotNull(actualTempLocalRepoPath);
			assertEquals(expectedTempLocalRepoPath.getAbsolutePath(), actualTempLocalRepoPath.getAbsolutePath());
			
			// Test with the real local repository
			File actualLocalRepoPath = getLocalRepositoryPaths(mavenRequest).get(0);
			assertNotNull(actualLocalRepoPath);
			assertEquals(expectedLocalRepoPath.getAbsolutePath(), actualLocalRepoPath.getAbsolutePath());
			
		} finally {
			if (localSettingsFile != null) {
				localSettingsFile.delete();
			}

			// Restore original System properties
			if (localRepoProperty != null) {
				properties.put(MAVEN_LOCAL_REPO_PROPERTY_NAME, localRepoProperty);
			}
			System.setProperties(properties);
		}
	}

	@Test
	public void testUserSettingsWithHomeEnvVariables() throws Exception {
		final File localSettingsFile = generateSettingsXml(HOME_ENV_VARIABLE_NAME);
		String localRepoProperty = System.getProperty(MAVEN_LOCAL_REPO_PROPERTY_NAME);
		Properties properties = System.getProperties();
		try {
			properties.remove(MAVEN_LOCAL_REPO_PROPERTY_NAME); // Not to use local repository set from properties
			System.setProperties(properties);

			String homeVariable = System.getenv(HOME_ENV_VARIABLE_NAME);
			
			MavenLemminxExtension plugin = new MavenLemminxExtension();
			plugin.settings.setGlobalSettings(null);
			plugin.settings.setUserSettings(localSettingsFile.getAbsolutePath());
			
			MavenExecutionRequest mavenRequest = plugin.getMavenSession().getRequest();			
			File expectedLocalRepoPath = new File(homeVariable, TEST_LOCAL_REPO_PATH);
			
			// Test with temporary lemminx-maven local repository
			File expectedTempLocalRepoPath = getTempLocalRepositoryPath(expectedLocalRepoPath);
			File actualTempLocalRepoPath = mavenRequest.getLocalRepositoryPath();
			assertNotNull(actualTempLocalRepoPath);
			assertEquals(expectedTempLocalRepoPath.getAbsolutePath(), actualTempLocalRepoPath.getAbsolutePath());
			
			// Test with the real local repository
			File actualLocalRepoPath = getLocalRepositoryPaths(mavenRequest).get(0);
			assertNotNull(actualLocalRepoPath);
			assertEquals(expectedLocalRepoPath.getAbsolutePath(), actualLocalRepoPath.getAbsolutePath());

		} finally {
			if (localSettingsFile != null) {
				localSettingsFile.delete();
			}

			// Restore original System properties
			if (localRepoProperty != null) {
				properties.put(MAVEN_LOCAL_REPO_PROPERTY_NAME, localRepoProperty);
			}
			System.setProperties(properties);
		}
	}
	
	@Test
	public void testDefaultCentralRepository() throws Exception {
		MavenExecutionRequest mavenRequest = initMavenRequest("""
				<?xml version="1.0" encoding="UTF-8"?>
				<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
				</settings>
				""");

		assertEquals(List.of(MAVEN_CENTRAL_URL), centralUrls(mavenRequest.getRemoteRepositories()));
		assertEquals(List.of(MAVEN_CENTRAL_URL), centralUrls(mavenRequest.getPluginArtifactRepositories()));
	}

	@Test
	public void testSettingsProfileRedefiningCentralRepository() throws Exception {
		MavenExecutionRequest mavenRequest = initMavenRequest("""
				<?xml version="1.0" encoding="UTF-8"?>
				<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
				  <profiles>
				    <profile>
				      <id>internal-central</id>
				      <repositories>
				        <repository>
				          <id>central</id>
				          <url>https://repo.example.com/central</url>
				        </repository>
				      </repositories>
				      <pluginRepositories>
				        <pluginRepository>
				          <id>central</id>
				          <url>https://repo.example.com/central-plugins</url>
				        </pluginRepository>
				      </pluginRepositories>
				    </profile>
				  </profiles>
				  <activeProfiles>
				    <activeProfile>internal-central</activeProfile>
				  </activeProfiles>
				</settings>
				""");

		// The redefined central repository is used and the default one isn't added
		assertEquals(List.of("https://repo.example.com/central"),
				centralUrls(mavenRequest.getRemoteRepositories()));
		assertEquals(List.of("https://repo.example.com/central-plugins"),
				centralUrls(mavenRequest.getPluginArtifactRepositories()));
	}

	private static final String MAVEN_CENTRAL_URL = "https://repo.maven.apache.org/maven2";

	private static MavenExecutionRequest initMavenRequest(String settingsXml) throws IOException {
		File settingsFile = File.createTempFile(InitMavenRequestTest.class.getSimpleName(), "-settings.xml");
		try {
			Files.writeString(settingsFile.toPath(), settingsXml);
			MavenLemminxExtension plugin = new MavenLemminxExtension();
			plugin.settings.setGlobalSettings(null);
			plugin.settings.setUserSettings(settingsFile.getAbsolutePath());
			return plugin.getMavenSession().getRequest();
		} finally {
			settingsFile.delete();
		}
	}

	@SuppressWarnings("deprecation") // MavenExecutionRequest only exposes the legacy ArtifactRepository
	private static List<String> centralUrls(List<ArtifactRepository> repositories) {
		return repositories.stream().filter(repository -> "central".equals(repository.getId()))
				.map(ArtifactRepository::getUrl).toList();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("windows");
	}
	
	private static String VARIABLE_TO_REPLACE = "%VARIABLE%";
	private static String settingsXmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
			+ "<settings xmlns=\"http://maven.apache.org/SETTINGS/1.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/SETTINGS/1.0.0                       http://maven.apache.org/xsd/settings-1.0.0.xsd\">\n"
			+ "    <localRepository>${%VARIABLE%}/.m2/bug383repository</localRepository>\n"
			+ "</settings>";
	private static String SETTINGS_FILE_PATH = System.getProperty("java.io.tmpdir") + "/" +
			InitMavenRequestTest.class.getSimpleName() + "/settings.xml";
	
	private File generateSettingsXml(String variableName) {
		try {
			File settingsXmlFile = new File(SETTINGS_FILE_PATH);
			settingsXmlFile.getParentFile().mkdirs();
			try (BufferedWriter bw = new BufferedWriter(new FileWriter(settingsXmlFile, false));) {
			    bw.write(settingsXmlContent.replace(VARIABLE_TO_REPLACE, variableName));
			    bw.close();
			}
			return settingsXmlFile;
		} catch (IOException  e) {
			e.printStackTrace();
			fail(e);
		}
		return null;
	}
}
