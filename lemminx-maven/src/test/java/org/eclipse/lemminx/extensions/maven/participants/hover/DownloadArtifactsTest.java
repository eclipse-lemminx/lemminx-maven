/*******************************************************************************
 * Copyright (c) 2019-2022 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.hover;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class DownloadArtifactsTest {

	private XMLLanguageService languageService;

	@BeforeEach
	public void setUp() throws IOException {
		languageService = new MavenLanguageService();
	}

	@AfterEach
	public void tearDown() throws InterruptedException, ExecutionException {
		languageService.dispose();
		languageService = null;
	}

	private DOMDocument createDOMDocument(String path) throws IOException, URISyntaxException {
		Properties props = new Properties();
		String remoteRepoURL = System.getProperty("remoteRepoURL");
		if (remoteRepoURL != null) {
			props.put("remoteRepoURL", remoteRepoURL);
		}
		return MavenLemminxTestsUtils.createDOMDocument(path, props, languageService);
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	public void testDownloadArtifactOnHover()
			throws IOException, InterruptedException, URISyntaxException {
		languageService.initializeIfNeeded();
		File mavenRepo = languageService.getExtensions().stream() //
				.filter(MavenLemminxExtension.class::isInstance) //
				.map(MavenLemminxExtension.class::cast) //
				.findAny() //
				.map(mavenLemminxPlugin -> mavenLemminxPlugin.getMavenSession().getRepositorySession()
						.getLocalRepository().getBasedir())
				.get();
		File artifactDirectory = new File(mavenRepo, "org/glassfish/jersey/project/2.19");
		final DOMDocument document = createDOMDocument("/pom-remote-artifact-download-hover.xml");
		final Position position = new Position(14, 18);
		assertFalse(artifactDirectory.exists());
		Hover hover;
		do {
			hover = languageService.doHover(document, position, new SharedSettings());
			Thread.sleep(500);
		} while (hover == null);

		assertTrue(artifactDirectory.exists());
		assertTrue(artifactDirectory.listFiles().length > 0);
	}

	@Test
	@Timeout(15000)
	public void testDownloadNonCentralArtifactOnHover()
			throws IOException, URISyntaxException {
		languageService.initializeIfNeeded();
		File mavenRepo = languageService.getExtensions().stream() //
				.filter(MavenLemminxExtension.class::isInstance) //
				.map(MavenLemminxExtension.class::cast) //
				.findAny() //
				.map(mavenLemminxPlugin -> mavenLemminxPlugin.getMavenSession().getRepositorySession()
						.getLocalRepository().getBasedir())
				.get();

		File artifactDirectory = new File(mavenRepo, "com/github/goxr3plus/java-stream-player/9.0.4");
		final DOMDocument document = createDOMDocument("/pom-remote-artifact-non-central-download-hover.xml");
		final Position position = new Position(14, 20);
		assertFalse(artifactDirectory.exists());
		languageService.doHover(document, position, new SharedSettings());

		assertTrue(artifactDirectory.exists());
		assertTrue(artifactDirectory.listFiles().length > 0);
	}

}
