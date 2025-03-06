/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.hover;

import static org.eclipse.lemminx.XMLAssert.assertHover;
import static org.eclipse.lemminx.XMLAssert.r;
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
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.extensions.contentmodel.settings.ContentModelSettings;
import org.eclipse.lemminx.extensions.maven.DOMConstants;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.MavenWorkspaceService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.MarkdownUtils;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.services.extensions.IWorkspaceServiceParticipant;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class WorkspaceProjectsHoverTest {
	private XMLLanguageService languageService;
	private File localRepoDirectory;

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

	private static String NL = MarkdownUtils.getLineBreak(true);
	
	@BeforeEach
	public void setUp() throws IOException {
		languageService = new MavenLanguageService();
		
		localRepoDirectory = System.getProperty("maven.repo.local") != null ?
				new File(System.getProperty("maven.repo.local")).getAbsoluteFile() :
				new File("target/testlocalrepo").getAbsoluteFile();
		assertTrue(localRepoDirectory.exists() && localRepoDirectory.isDirectory(), "Local Repo doesn't exist or not a directory: " + localRepoDirectory.getAbsolutePath());
	}

	@AfterEach
	public void tearDown() throws InterruptedException, ExecutionException {
		languageService.dispose();
		languageService = null;
	}
	
	//
	// Test hovering over parent element
	//
	
	/**
	 * Test hover on parent for  artifact 'tools'. 
	 * The resulting hover should show parent artifact 'root'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsToParentDocument()
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

		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		assertHover(languageService, text, null, document.getDocumentURI(), 
				"**" + TOOLS_PARENT + "**",
				r(8, 14, 8, 18), settings);
	}

	/**
	 * Test hover on parent for  artifact 'tools.retry.springframework.impl.dummy'. 
	 * The resulting hover should show parent artifact 'tools.retry.springframework.impl'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsRetrySpringframeworkImplDummyToParentDocument()
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

		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		assertHover(languageService, text, null, document.getDocumentURI(), 
				"**" + TOOLS_RETRY_SPRINGFRAMEWORK_IMPL_DUMMY_PARENT + "**",
				r(8, 14, 8, 46), settings);
	}

	/**
	 * Test hover on parent for  artifact 'tools.springframework'. 
	 * The resulting hover should show parent artifact 'tools.internal'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsSpringframeworkToParentDocument()
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

		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		assertHover(languageService, text, null, document.getDocumentURI(), 
				"**" + TOOLS_SPRINGFRAMEWORK_PARENT + "**",
				r(8, 14, 8, 28), settings);
	}
	
	/**
	 * Test hover on parent for  artifact 'tools.BOM'. 
	 * The resulting hover should show parent artifact 'tools'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsBomToParentDocument()
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
	
		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		assertHover(languageService, text, null, document.getDocumentURI(), 
				"**" + TOOLS_BOM_PARENT + "**",
				r(8, 14, 8, 19), settings);
	}

	/**
	 * Test hover on parent for  artifact 'project'. 
	 * The resulting hover should show parent artifact 'root'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromProjectToParentDocument()
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
	
		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		assertHover(languageService, text, null, document.getDocumentURI(), 
				"**" + PROJECT_PARENT + "**",
				r(8, 14, 8, 18), settings);
	}

	//
	// Test hovering over external dependency element
	//
	
	/**
	 * Test hover on external dependency of artifact 'tools.retry.springframework'. 
	 * The resulting hover should show dependency artifact 'org.springframework.boot:spring-boot-dependencies:2.4.2'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsRetrySpringFrameworkToSpringBootDependencieskDependency()
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
	
		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		String expectedHoverText = "**spring-boot-dependencies**" + NL //
				+ "Spring Boot Dependencies" + NL //
				+ "**The managed scope is: \"import\"**";
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		assertHover(languageService, text, null, document.getDocumentURI(), 
				expectedHoverText,
				r(26, 16, 26, 40), settings);
	}

	/**
	 * Test hover on local dependency of artifact 'tools.retry.springframework'. 
	 * The resulting hover should show dependency artifact 'tools.retry.springframework.interface'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsRetrySpringFrameworkTooolsRetrySpringFrameworkInterfaceDependency()
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
	
		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		assertHover(languageService, text, null, document.getDocumentURI(), 
				"**tools.retry.springframework.interface**",
				r(33, 16, 33, 53), settings);
	}

	/**
	 * Test hover on local dependency of artifact `spring-context` in 
	 * 'issue-345.tools.retry.springframework.impl.dummy'
	 * The resulting hover should show managed dependency artifact 
	 * 'org.springframework:spring-framework-bom:5.3.3'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsRetrySpringFrameworImlDummyToSpringContextDependency()
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
	
		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		
		assertNotNull(localRepoDirectory, "Local Repository directory cannot be null!");
		String expectedHoverText = "**Spring Framework (Bill of Materials)**" + NL //
				+ "Spring Framework (Bill of Materials)" + NL //
				+ "**The managed version is 5.3.3.** " //
				+ "**The artifact is managed in [org.springframework:spring-framework-bom:5.3.3](" //
				+ localRepoDirectory.toURI().toString()  //
				+ "org/springframework/spring-framework-bom/5.3.3/spring-framework-bom-5.3.3.pom#L63,22-L63,22)**" + NL //
				+ "**The managed scope is: \"compile\"**";
		Range expectedHoverRange = r(34, 15, 34, 29);
		
		assertHover(languageService, text, null, document.getDocumentURI(), 
				expectedHoverText, expectedHoverRange, settings);
	}
	
	/**
	 * Test hover on local dependency of artifact `tools.retry.springframework.interface` 
	 * in  project 'issue-345.tools.retry.springframework.impl.dummy'
	 * The resulting hover should show managed dependency artifact 
	 * 'org.springframework:spring-framework-bom:5.3.3'
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws ExecutionException
	 * @throws URISyntaxException
	 * @throws BadLocationException
	 */
	@Test
	public void testHoverFromToolsRetrySpringFrameworImlDummyToToolsRetrySpringframeworkInterfaceDependency()
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
	
		String text = document.getText();
		text = text.substring(0, offset) + '|' + text.substring(offset);
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		
		assertNotNull(wsFolder, "WORKSPACE directory cannot be null!");
		String expectedHoverText = "" + NL //
				+ "**The managed version is 0.0.1-SNAPSHOT.** " //
				+ "**The artifact is managed in [mygroup:tools.retry.springframework:0.0.1-SNAPSHOT](" // 
				+ new File(getClass().getResource(WORKSPACE_PATH).getFile()).toURI().toString()  //
				+ "tools/modules/retry-springframework/pom.xml#L35,18-L35,18)**" + NL //
				+ "**The managed scope is: \"compile\"**";
		Range expectedHoverRange = r(20, 15, 20, 52);
		
		assertHover(languageService, text, null, document.getDocumentURI(), 
				expectedHoverText, expectedHoverRange, settings);
	}
}
