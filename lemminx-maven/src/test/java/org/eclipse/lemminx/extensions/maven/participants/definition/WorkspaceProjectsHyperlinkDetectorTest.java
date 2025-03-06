/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.definition;

import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.extensions.maven.DOMConstants;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.MavenWorkspaceService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.services.extensions.IWorkspaceServiceParticipant;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class WorkspaceProjectsHyperlinkDetectorTest {
	private XMLLanguageService languageService;

	public static String WORKSPACE_PATH = "/issue-345";
	public static String ROOT_PATH = "/root/pom.xml";
	public static String TOOLS_PATH = "/tools/pom.xml";
	public static String TOOLS_PARENT = "root";
	public static String TOOLS_RETRY_SPRINGFRAMEWORK_IMPL_DUMMY_PATH = 
			"/tools/modules/retry-springframework-impl-dummy/pom.xml";
	public static String TOOLS_RETRY_SPRINGFRAMEWORK_IMPL_DUMMY_PARENT = 
			"tools.retry.springframework.impl";
	public static String TOOLS_SPRINGFRAMEWORK_PATH = "/tools/modules/springframework/pom.xml";
	public static String TOOLS_SPRINGFRAMEWORK_PARENT = "tools.internal";
	public static String TOOLS_BOM_PATH = "/tools/modules/BOM/pom.xml";
	public static String TOOLS_BOM_PARENT = "tools";
	public static String PROJECT_PATH = "/project/pom.xml";
	public static String PROJECT_PARENT = "root";

	public static String TOOLS_RETRY_SPRINGFRAMEWORK_PATH = "/tools/modules/retry-springframework/pom.xml";
	public static String TOOLS_RETRY_SPRINGFRAMEWORK_SPRING_BOOT_DEPENDENCIES_TARGET_ARTIFACT = "spring-boot-dependencies";
	public static String TOOLS_RETRY_SPRINGFRAMEWORK_SPRING_BOOT_DEPENDENCIES_TARGET_GROUP = "org.springframework.boot";

	@BeforeEach
	public void setUp() throws IOException {
		languageService = new MavenLanguageService();
	}

	@AfterEach
	public void tearDown() throws InterruptedException, ExecutionException {
		languageService.dispose();
		languageService = null;
	}
	
	//
	// Test Ctrl-clicking on parent element
	//
	
	/**
	 * Test Ctrl-clicking on parent for  artifact 'tools'. 
	 * The resulting URI should lead to parent artifact 'root'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsToParentDocument()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument document = createDOMDocument(WORKSPACE_PATH + TOOLS_PATH, languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		DOMElement parentArtifactId = DOMUtils.findChildElement(parent, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(parentArtifactId, "Parent ArtifactId element not found!");
		int offset = (parentArtifactId.getStart() + parentArtifactId.getEnd()) / 2;

		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsToParentDocument(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uriiRawPathEndsWith(uri, "/issue-345/root/pom.xml")));
	}

	/**
	 * Test Ctrl-clicking on parent for  artifact 'tools.retry.springframework.impl.dummy'. 
	 * The resulting URI should lead to parent artifact 'tools.retry.springframework.impl'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsRetrySpringframeworkImplDummyToParentDocument()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument document = createDOMDocument(WORKSPACE_PATH + TOOLS_RETRY_SPRINGFRAMEWORK_IMPL_DUMMY_PATH, languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		DOMElement parentArtifactId = DOMUtils.findChildElement(parent, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(parentArtifactId, "Parent ArtifactId element not found!");
		int offset = (parentArtifactId.getStart() + parentArtifactId.getEnd()) / 2;

		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsRetrySpringframeworkImplDummyToParentDocument(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uriiRawPathEndsWith(uri, "/issue-345/tools/modules/retry-springframework-impl/pom.xml")));
	}

	/**
	 * Test Ctrl-clicking on parent for  artifact 'tools.springframework'. 
	 * The resulting URI should lead to parent artifact 'tools.internal'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsSpringframeworkToParentDocument()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument document = createDOMDocument(WORKSPACE_PATH + TOOLS_SPRINGFRAMEWORK_PATH, languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		DOMElement parentArtifactId = DOMUtils.findChildElement(parent, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(parentArtifactId, "Parent ArtifactId element not found!");
		int offset = (parentArtifactId.getStart() + parentArtifactId.getEnd()) / 2;

		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsSpringframeworkToParentDocument(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uriiRawPathEndsWith(uri, "/issue-345/tools/modules/internal/pom.xml")));
	}
	
	/**
	 * Test Ctrl-clicking on parent for  artifact 'tools.BOM'. 
	 * The resulting URI should lead to parent artifact 'tools'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsBomToParentDocument()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());
	
		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
	
		DOMDocument document = createDOMDocument(WORKSPACE_PATH + TOOLS_BOM_PATH, languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		DOMElement parentArtifactId = DOMUtils.findChildElement(parent, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(parentArtifactId, "Parent ArtifactId element not found!");
		int offset = (parentArtifactId.getStart() + parentArtifactId.getEnd()) / 2;
	
		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsBomToParentDocument(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uriiRawPathEndsWith(uri, "/issue-345/tools/pom.xml")));
	}

	/**
	 * Test Ctrl-clicking on parent for  artifact 'project'. 
	 * The resulting URI should lead to parent artifact 'root'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromProjectToParentDocument()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());
	
		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
	
		DOMDocument document = createDOMDocument(WORKSPACE_PATH + PROJECT_PATH, languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		DOMElement parentArtifactId = DOMUtils.findChildElement(parent, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(parentArtifactId, "Parent ArtifactId element not found!");
		int offset = (parentArtifactId.getStart() + parentArtifactId.getEnd()) / 2;
	
		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromProjectToParentDocument(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uriiRawPathEndsWith(uri, "/issue-345/root/pom.xml")));
	}

	//
	// Test Ctrl-clicking on a dependency element
	//
	
	/**
	 * Test Ctrl-clicking on external dependency of artifact 'tools.retry.springframework'. 
	 * The resulting URI should lead to dependency artifact 'org.springframework.boot:spring-boot-dependencies:2.4.2'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsRetrySpringFrameworkToSpringBootDependencieskDependency()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());
	
		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
	
		DOMDocument document = createDOMDocument(WORKSPACE_PATH + TOOLS_RETRY_SPRINGFRAMEWORK_PATH, languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		
		DOMElement dependencyManagement = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.DEPENDENCY_MANAGEMENT_ELT).orElse(null);
		assertNotNull(dependencyManagement, "Dependency Management element not found!");
		
		DOMElement dependencies = DOMUtils.findChildElement(dependencyManagement, DOMConstants.DEPENDENCIES_ELT).orElse(null);
		assertNotNull(dependencies, "Dependencies element not found!");

		List<DOMElement> dependencyList = DOMUtils.findChildElements(dependencies, DOMConstants.DEPENDENCY_ELT);
		DOMElement targetDependency = null;
		for (DOMElement d : dependencyList) {
			String artifactId = DOMUtils.findChildElementText(d, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
			String group = DOMUtils.findChildElementText(d, DOMConstants.GROUP_ID_ELT).orElse(null);
			if (TOOLS_RETRY_SPRINGFRAMEWORK_SPRING_BOOT_DEPENDENCIES_TARGET_ARTIFACT.equals(artifactId)
					&& TOOLS_RETRY_SPRINGFRAMEWORK_SPRING_BOOT_DEPENDENCIES_TARGET_GROUP.equals(group)) {
				targetDependency = d;
				break;
			}
		}
		assertNotNull(targetDependency, "Target Dependency element not found!");
		 
		DOMElement targetDependencyArtifactId = DOMUtils.findChildElement(targetDependency, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(targetDependencyArtifactId, "Target Dependency ArtifactId element not found!");
		int offset = (targetDependencyArtifactId.getStart() + targetDependencyArtifactId.getEnd()) / 2;
	
		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsRetrySpringFrameworkToSpringBootDependencieskDependency(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uriiRawPathEndsWith(uri, "/spring-boot-dependencies-2.4.2.pom")));
	}

	/**
	 * Test Ctrl-clicking on local dependency of artifact 'tools.retry.springframework'. 
	 * The resulting URI should lead to dependency artifact 'tools.retry.springframework.interface'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsRetrySpringFrameworkTooolsRetrySpringFrameworkInterfaceDependency()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());
	
		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
	
		DOMDocument document = createDOMDocument(WORKSPACE_PATH + TOOLS_RETRY_SPRINGFRAMEWORK_PATH, languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		
		DOMElement dependencyManagement = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.DEPENDENCY_MANAGEMENT_ELT).orElse(null);
		assertNotNull(dependencyManagement, "Dependency Management element not found!");
		
		DOMElement dependencies = DOMUtils.findChildElement(dependencyManagement, DOMConstants.DEPENDENCIES_ELT).orElse(null);
		assertNotNull(dependencies, "Dependencies element not found!");

		List<DOMElement> dependencyList = DOMUtils.findChildElements(dependencies, DOMConstants.DEPENDENCY_ELT);
		DOMElement targetDependency = null;
		for (DOMElement d : dependencyList) {
			String artifactId = DOMUtils.findChildElementText(d, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
			String group = DOMUtils.findChildElementText(d, DOMConstants.GROUP_ID_ELT).orElse(null);
			if ("tools.retry.springframework.interface".equals(artifactId)
					&& "mygroup".equals(group)) {
				targetDependency = d;
				break;
			}
		}
		assertNotNull(targetDependency, "Target Dependency element not found!");
		 
		DOMElement targetDependencyArtifactId = DOMUtils.findChildElement(targetDependency, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(targetDependencyArtifactId, "Target Dependency ArtifactId element not found!");
		int offset = (targetDependencyArtifactId.getStart() + targetDependencyArtifactId.getEnd()) / 2;
	
		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsRetrySpringFrameworkTooolsRetrySpringFrameworkInterfaceDependency(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uriiRawPathEndsWith(uri, "/issue-345/tools/modules/retry-springframework-interface/pom.xml")));
	}

	/**
	 * Test Ctrl-clicking on external dependency of artifact 'spring-context' in project
	 * 'issue-345.tools.retry.springframework.impl.dummy'.
	 * The resulting URI should lead to dependency artifact 
	 * 'org.springframework:spring-framework-bom:5.3.3'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsRetrySpringFrameworImlDummyToSpringContextDependency()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());
	
		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
	
		DOMDocument document = createDOMDocument(WORKSPACE_PATH 
				+ "/tools/modules/retry-springframework-impl-dummy/pom.xml", languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		
		DOMElement dependencies = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.DEPENDENCIES_ELT).orElse(null);
		assertNotNull(dependencies, "Dependencies element not found!");

		List<DOMElement> dependencyList = DOMUtils.findChildElements(dependencies, DOMConstants.DEPENDENCY_ELT);
		DOMElement targetDependency = null;
		for (DOMElement d : dependencyList) {
			String artifactId = DOMUtils.findChildElementText(d, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
			String groupId = DOMUtils.findChildElementText(d, DOMConstants.GROUP_ID_ELT).orElse(null);
			if ("spring-context".equals(artifactId)
					&& "org.springframework".equals(groupId)) {
				targetDependency = d;
				break;
			}
		}
		assertNotNull(targetDependency, "Target Dependency element not found!");
		 
		DOMElement targetDependencyArtifactId = DOMUtils.findChildElement(targetDependency, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(targetDependencyArtifactId, "Target Dependency ArtifactId element not found!");
		int offset = (targetDependencyArtifactId.getStart() + targetDependencyArtifactId.getEnd()) / 2;
	
		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsRetrySpringFrameworImlDummyToSpringContextDependency(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri)
				.anyMatch(uri -> uriiRawPathEndsWith(uri, "/org/springframework/spring-framework-bom/5.3.3/spring-framework-bom-5.3.3.pom")));
	}

	/**
	 * Test Ctrl-clicking on external dependency of artifact 'tools.retry.springframework.interface' 
	 * in project 'issue-345.tools.retry.springframework.impl.dummy'.
	 * The resulting URI should lead to dependency artifact 
	 * 'tools.retry.springframework:0.0.1-SNAPSHOT'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHyperlinkFromToolsRetrySpringFrameworImlDummyToToolsRetrySpringframeworkInterfaceDependency()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, BadLocationException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource(WORKSPACE_PATH).toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());
	
		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
	
		DOMDocument document = createDOMDocument(WORKSPACE_PATH 
				+ "/tools/modules/retry-springframework-impl-dummy/pom.xml", languageService);
		DOMElement parent = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.PARENT_ELT).orElse(null);
		assertNotNull(parent, "Parent element not found!");
		
		DOMElement dependencies = DOMUtils.findChildElement(document.getDocumentElement(), DOMConstants.DEPENDENCIES_ELT).orElse(null);
		assertNotNull(dependencies, "Dependencies element not found!");

		List<DOMElement> dependencyList = DOMUtils.findChildElements(dependencies, DOMConstants.DEPENDENCY_ELT);
		DOMElement targetDependency = null;
		for (DOMElement d : dependencyList) {
			String artifactId = DOMUtils.findChildElementText(d, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
			String groupId = DOMUtils.findChildElementText(d, DOMConstants.GROUP_ID_ELT).orElse(null);
			if ("tools.retry.springframework.interface".equals(artifactId)
					&& "mygroup".equals(groupId)) {
				targetDependency = d;
				break;
			}
		}
		assertNotNull(targetDependency, "Target Dependency element not found!");
		 
		DOMElement targetDependencyArtifactId = DOMUtils.findChildElement(targetDependency, DOMConstants.ARTIFACT_ID_ELT).orElse(null);
		assertNotNull(targetDependencyArtifactId, "Target Dependency ArtifactId element not found!");
		int offset = (targetDependencyArtifactId.getStart() + targetDependencyArtifactId.getEnd()) / 2;

		TextDocument textDocument = document.getTextDocument();
		Position offsetPosition = textDocument.positionAt(offset);
		List<? extends LocationLink> definitions = languageService.findDefinition(document, offsetPosition, ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(uri -> System.out.println("testHyperlinkFromToolsRetrySpringFrameworImlDummyToSpringContextDependency(): " + uri));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri)
				.anyMatch(uri -> uriiRawPathEndsWith(uri, "/issue-345/tools/modules/retry-springframework/pom.xml")));
	}
	
	private static final boolean uriiRawPathEndsWith(String uri, String rawRelativePath) {
		return uri.replace("\\", "/").endsWith(rawRelativePath.replace("\\", "/"));
	}
}
