/*******************************************************************************
 * Copyright (c) 2019-2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.project;

import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.maven.project.MavenProject;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationSettings;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.MavenWorkspaceService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.services.extensions.IWorkspaceServiceParticipant;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class MavenProjectCacheTest {
	private static MavenLanguageService languageService;
	
	@BeforeEach
	public void setUp() {
		MavenLemminxExtension.setUnitTestMode(true);
		// Some tests require like a "cold: start with no any information cached
		// So the language service is to be created at @BeforeEach
		languageService = new MavenLanguageService();
	}
	
	@Test
	public void testSimpleProjectIsParsed() throws Exception {
		MavenLemminxExtension plugin = new MavenLemminxExtension();
		plugin.start(null,languageService);
		
		URI uri = getClass().getResource("/pom-with-properties.xml").toURI();
		String content = Files.readString(new File(uri).toPath(), StandardCharsets.UTF_8);
		DOMDocument doc = new DOMDocument(new TextDocument(content, uri.toString()), null);
		languageService.didOpen(doc);

		MavenProjectCache cache = plugin.getProjectCache();
		MavenProject project = cache.getLastSuccessfulMavenProject(doc);
		assertNotNull(project);
	}

	@Test
	public void testOnBuildError_ResolveProjectFromDocumentBytes() throws Exception {
		MavenLemminxExtension plugin = new MavenLemminxExtension();
		plugin.start(null,languageService);
		
		URI uri = getClass().getResource("/pom-with-module-error.xml").toURI();
		File pomFile = new File(uri);
		String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
		DOMDocument doc = new DOMDocument(new TextDocument(content, uri.toString()), null);
		languageService.didOpen(doc);
		
		MavenProjectCache cache = plugin.getProjectCache();
		MavenProject project = cache.getLastSuccessfulMavenProject(doc);
		assertNotNull(project);
	}

	@Test
	public void testParentChangeReflectedToChild()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, Exception {
		MavenLemminxExtension plugin = new MavenLemminxExtension();
		plugin.start(null,languageService);

		MavenProjectCache cache = plugin.getProjectCache();
		DOMDocument doc = createDOMDocument("/pom-with-properties-in-parent.xml", languageService);
		languageService.didOpen(doc);

		MavenProject project = cache.getLastSuccessfulMavenProject(doc);
		assertTrue(project.getProperties().containsKey("myProperty"), project.getProperties().toString());
		URI parentUri = getClass().getResource("/pom-with-properties.xml").toURI();
		File parentPomFile = new File(parentUri);
		String initialContent = Files.readString(parentPomFile.toPath(), StandardCharsets.UTF_8);
		try {
			String content = initialContent.replaceAll("myProperty", "modifiedProperty");
			Files.writeString(parentPomFile.toPath(), content);
			doc.getTextDocument().setVersion(2); // Simulate some change
			MavenProject modifiedProject = cache.getLastSuccessfulMavenProject(doc);
			assertTrue(modifiedProject.getProperties().containsKey("modifiedProperty"),
					modifiedProject.getProperties().toString());
		} finally {
			Files.writeString(parentPomFile.toPath(), initialContent);
		}
	}

	@Test
	public void testAddFolders_didChangeWorkspaceFolders() throws Exception {
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Test add folders
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
		
		// As ModuleA (module-a-pom.xml) is a parent of ModuleC (module-c-pom.xml) and
		// ModuleA folder was added it should be resolved and, as such, no diagnostics 
		// error messages should appear.
		//
		DOMDocument doc = createDOMDocument("/modules/dependent/module-c-pom.xml", languageService);
		languageService.didOpen(doc);

		List<Diagnostic> diagnostics = languageService.doDiagnostics(doc, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnostics.stream().anyMatch(diag -> (diag.getMessage().contains("ModuleA"))));
	}
	
//	@Test
	public void testRemoveFolders_didChangeWorkspaceFolders() throws Exception {
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		// Test remove folders 
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[0]),
								Arrays.asList(new WorkspaceFolder[] {wsFolder}))));
		
		// As ModuleA (module-a-pom.xml) is a parent of ModuleC (module-c-pom.xml) and
		// ModuleA folder was removed it should not be resolved and, as such, a diagnostics 
		// error message should appear.
		//
		DOMDocument doc = createDOMDocument("/modules/dependent/module-c-pom.xml", languageService);
		languageService.didOpen(doc);

		List<Diagnostic> diagnostics = languageService.doDiagnostics(doc, new XMLValidationSettings(), Map.of(), () -> {});
		assertTrue(diagnostics.stream().anyMatch(diag -> (diag.getMessage().contains("ModuleA"))));
	}
	
	@Test
	public void testNormilizePathsAreUsedInCache()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, Exception {
		MavenLemminxExtension plugin = new MavenLemminxExtension();
		plugin.start(null,languageService);

		MavenProjectCache cache = plugin.getProjectCache();
		int initialProjectsSize = cache.getProjects().size();
		
		// Use URLretative to a child to access a parent pom.xml
		// Check if the cached projects size increased by 1 project
		DOMDocument documentByRelativeURL = getDocumentNotNormilized("/hierarchy/child/grandchild/../../pom.xml");
		MavenProject projectByRelativeURL = cache.getLastSuccessfulMavenProject(documentByRelativeURL);
		assertNotNull(projectByRelativeURL);
		int projectsSize = cache.getProjects().size();
		assertEquals(initialProjectsSize + 1, projectsSize);
		
		// Use yet another URLretative to a child to access a parent pom.xml
		// Check if the cached projects size is NOT increased (the project is already cached)
		DOMDocument documentByRelativeURL_2 = getDocumentNotNormilized("/hierarchy/child/../pom.xml");
		MavenProject projectByRelativeURL_2 = cache.getLastSuccessfulMavenProject(documentByRelativeURL_2);
		assertNotNull(projectByRelativeURL_2);
		projectsSize = cache.getProjects().size();
		assertEquals(initialProjectsSize + 1, projectsSize);

		// Use direct URL  to access a parent pom.xml
		// Check if the cached projects size is NOT increased (the project is already cached)
		DOMDocument documentByNormilizedUURL = getDocumentNotNormilized("/hierarchy/pom.xml");
		MavenProject projectByNormilizedURL = cache.getLastSuccessfulMavenProject(documentByNormilizedUURL);
		assertNotNull(projectByNormilizedURL);
		projectsSize = cache.getProjects().size();
		assertEquals(initialProjectsSize + 1, projectsSize);
		
		// Check if cache returns the same project for relative and normalized URL
		assertEquals(projectByRelativeURL, projectByNormilizedURL);
		
		// Try getting the snapshot project by the relative and normalized URIs - it 
		// Should result into the same cached project 

		File baseDirectory = new File(getClass().getResource("/").toURI());

		// With a relative path 
		File fileByRelativeURL = new File(baseDirectory,"/hierarchy/child/grandchild/../../pom.xml");
		MavenProject snapshotProjectByRelativeURL = cache.getSnapshotProject(fileByRelativeURL).get();
		assertNotNull(snapshotProjectByRelativeURL);
		assertEquals(projectByNormilizedURL, snapshotProjectByRelativeURL);

		// With yet another relative path 
		File fileByRelativeURL_2 = new File(baseDirectory,"/hierarchy/child/../pom.xml");
		MavenProject snapshotProjectByRelativeURL_2 = cache.getSnapshotProject(fileByRelativeURL_2).get();
		assertNotNull(snapshotProjectByRelativeURL_2);
		assertEquals(projectByNormilizedURL, snapshotProjectByRelativeURL_2);

		// With a normalized path 
		File fileByNormilizedURL = new File(baseDirectory,"/hierarchy/pom.xml");
		MavenProject snapshotProjectByNormilizedURL = cache.getSnapshotProject(fileByNormilizedURL).get();
		assertNotNull(snapshotProjectByNormilizedURL);
		assertEquals(projectByNormilizedURL, snapshotProjectByNormilizedURL);
	}
	
	/*
	 * This method creates a DOMDocument using a not normalized URI, is to be used in Maven Project Cache 
	 * duplication test case.
	 * The 'normal' createDocument() method normalizes the resource URI, so it's not possible to test for duplications
	 * using that method.
	 */
	private DOMDocument getDocumentNotNormilized(String resource) throws URISyntaxException, IOException {
		URI baseUri = getClass().getResource("/").toURI();
		File baseDirectory = new File(baseUri);
		File pomFile = new File(baseDirectory, resource);
		String content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
		DOMDocument doc = new DOMDocument(new TextDocument(content, pomFile.toURI().toString()), null);
		return doc;
	}
}
