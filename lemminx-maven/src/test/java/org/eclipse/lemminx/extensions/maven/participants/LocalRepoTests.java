/*******************************************************************************
 * Copyright (c) 2019, 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants;

import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;

import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class LocalRepoTests {


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

	@Test
	@Timeout(90000)
	public void testCompleteDependency()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doComplete(createDOMDocument("/pom-with-dependency.xml", languageService), new Position(11, 7), new SharedSettings())
				.getItems().stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("org.apache.maven:maven-core")));
	}

	@Test
	@Timeout(90000)
	public void testCompleteLocalGroupdId()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doComplete(createDOMDocument("/pom-local-groupId-complete.xml", languageService), new Position(11, 12), new SharedSettings())
				.getItems().stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("org.apache.maven")));
	}

	@Test
	@Timeout(90000)
	public void testDoNotCompleteNonExistingArtifact()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doComplete(createDOMDocument("/pom-non-existing-dep.xml", languageService), new Position(10, 27), new SharedSettings())
				.getItems().stream().map(CompletionItem::getLabel).noneMatch(label -> label.contains("some.fake.group")));
	}

	@Test
	public void testDefinitionManagedDependency() throws IOException, URISyntaxException {
		assertTrue(languageService.findDefinition(createDOMDocument("/pom-dependencyManagement-child.xml", languageService), new Position(17, 6), () -> {})
				.stream().map(LocationLink::getTargetUri)
				.anyMatch(uri -> uri.endsWith("/pom-dependencyManagement-parent.xml")));
	}
}
