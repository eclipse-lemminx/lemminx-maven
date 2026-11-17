/*******************************************************************************
 * Copyright (c) 2023, 2026 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.utils;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.cli.configuration.SettingsXmlConfigurationProcessor;
import org.eclipse.lemminx.extensions.maven.settings.XMLMavenSettings;

/**
 * Utilities to compute the local repository path.
 * 
 */
public class LocalRepositoryUtils {

	private static final String LEMMINX_MAVEN = ".lemminx-maven";
	private static final String MAVEN_REPO_LOCAL_TAIL = "maven.repo.local.tail";
	public static final String MAVEN_LOCAL_REPO_PROPERTY_NAME = "maven.repo.local";

	private LocalRepositoryUtils() {

	}

	/**
	 * Update the local repository path of the maven request:
	 * 
	 * <ul>
	 * <li>the main local repository is a temporary directory
	 * ${userName}.m2/.lemminx-maven where artifacts,metadata files are downloaded
	 * when pom.xml is editing.</li>
	 * <li>the user local repository is registered as 'maven repo tail' to avoid
	 * download artifacts, metada files in this folder.
	 * </ul>
	 * 
	 * @param mavenRequest the maven request to update
	 * @param options      the maven settings.
	 */
	public static void updateLocalRepositoryPath(MavenExecutionRequest mavenRequest, XMLMavenSettings options) {
		File localRepositoryDir = getLocalRepositoryDir(options, mavenRequest.getLocalRepositoryPath());

		// When artifact are resolved, aether create folders and some metadata files
		// To avoid polluting the user local repository with those metadata files we:

		// 1. create a temporary dir (ex: ${userName}.m2/.lemminx-maven) and we
		// configure
		// maven request with this local repository
		// When user will type some wrong artifact, the metadata files will be
		// downloaded in this temporary dir
		File tempLocalRepositoryPath = getTempLocalRepositoryPath(localRepositoryDir);
		tempLocalRepositoryPath.mkdirs();
		mavenRequest.setLocalRepositoryPath(tempLocalRepositoryPath);

		// 2. add the use local repository as 'maven repo tail' (maven.repo.local.tail)
		// to use it this local repository to find artifacts
		mavenRequest.getUserProperties().put(MAVEN_REPO_LOCAL_TAIL, localRepositoryDir.toString());
	}

	/**
	 * Returns the all local repository path that user have configured from the
	 * maven request.
	 * 
	 * @param mavenRequest the maven request.
	 * @return the all local repository path that user have configured from the
	 *         maven request.
	 */
	public static List<File> getLocalRepositoryPaths(MavenExecutionRequest mavenRequest) {
		String tail = (String) mavenRequest.getUserProperties().get(MAVEN_REPO_LOCAL_TAIL);
		return Stream.of(tail.split(",")) //
				.map(dir -> new File(dir)) //
				.toList();

	}

	/**
	 * Returns the temporary local repository path computed according the user local
	 * repository path.
	 * 
	 * @param localRepositoryDir the user local repository path
	 * @return the temporary local repository path computed according the user local
	 *         repository path.
	 */
	public static File getTempLocalRepositoryPath(File localRepositoryDir) {
		return new File(localRepositoryDir.getParentFile(), LEMMINX_MAVEN);
	}

	private static File getLocalRepositoryDir(XMLMavenSettings options, File defaultLocalRepositoryPath) {
		// 1) Try to search local repository from the settings
		String fromSettings = options.getRepo().getLocal();
		if (fromSettings != null && !fromSettings.trim().isEmpty()) {
			File candidate = new File(fromSettings);
			if (candidate.isDirectory() && candidate.canRead()) {
				return candidate;
			}
		}

		// 2) Try to search the local repository from the System
		String fromSystem = System.getProperty(MAVEN_LOCAL_REPO_PROPERTY_NAME);
		if (fromSystem != null) {
			return new File(fromSystem);
		}

		if (defaultLocalRepositoryPath != null) {
			return defaultLocalRepositoryPath;
		}
		// 3) Returns the default local repository ${userName}/.m2/repository
		return new File(SettingsXmlConfigurationProcessor.USER_MAVEN_CONFIGURATION_HOME, "repository");
	}

}
