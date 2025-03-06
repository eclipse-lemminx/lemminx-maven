/*******************************************************************************
 * Copyright (c) 2019-2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants;

import static org.eclipse.lemminx.XMLAssert.d;
import static org.eclipse.lemminx.XMLAssert.r;
import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationSettings;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.MavenWorkspaceService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils;
import org.eclipse.lemminx.services.extensions.IWorkspaceServiceParticipant;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class SimpleModelTest {

	private MavenLanguageService languageService;

	@BeforeEach
	public void setUp() throws IOException {
		languageService = new MavenLanguageService();
		MavenLemminxTestsUtils.prefetchMavenXSD();
	}

	@AfterEach
	public void tearDown() throws InterruptedException, ExecutionException {
		languageService.dispose();
		languageService = null;
	}

	@Test
	@Timeout(10000)
	public void testScopeCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-module-error.xml", languageService);
		languageService.didOpen(document);
		
		CompletionList completion = languageService.doComplete(document,
				new Position(12, 10), new SharedSettings());
		assertTrue(completion.getItems().stream().map(CompletionItem::getLabel).anyMatch("runtime"::equals));
	}

	@Test
	@Timeout(10000)
	public void testPropertyCompletion()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-properties.xml", languageService);
		languageService.didOpen(document);

		CompletionList completion = languageService.doComplete(document,
				new Position(11, 15), new SharedSettings());
		assertTrue(completion.getItems().stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("myProperty")));
		assertTrue(completion.getItems().stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("project.build.directory")));
	}

	@Test
	@Timeout(10000)
	public void testParentPropertyCompletion()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-properties-in-parent.xml", languageService);
		languageService.didOpen(document);

		assertTrue(languageService.doComplete(document, new Position(15, 20), new SharedSettings())
				.getItems().stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains("myProperty")));
	}

	@Test
	@Timeout(15000)
	public void testLocalParentGAVCompletion()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException, TimeoutException {
		// * if relativePath is set and resolve to a pom or a folder containing a pom, GAV must be available for completion
		DOMDocument document = createDOMDocument("/hierarchy/child/grandchild/pom.xml", languageService);
		languageService.didOpen(document);
	
		assertTrue(languageService.doComplete(document,
				new Position(4, 2), new SharedSettings()).getItems().stream().map(CompletionItem::getLabel).anyMatch(label -> label.startsWith("test-parent")));

		// * if relativePath is not set and parent contains a pom, complete GAV from parent
		document = createDOMDocument("/hierarchy/child/pom.xml", languageService);
		languageService.didOpen(document);

		assertTrue(languageService.doComplete(document,
				new Position(4, 2), new SharedSettings()).getItems().stream().map(CompletionItem::getLabel).anyMatch(label -> label.startsWith("test-parent")));
		// TODO:
		// * if relativePath is not set, complete with local repo artifacts with "pom" packaging
		// * if relativePath is not set, complete with remote repo artifacts with "pom" packaging
	}

	@Test
	public void testDoNotReportNonParseablePomError()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		TextDocument textDocument = new TextDocument("<project> < </project>", "file:///pom.xml");
		DOMDocument document = DOMParser.getInstance().parse(textDocument, languageService.getResolverExtensionManager());
		languageService.didOpen(document);
		
		List<Diagnostic> diagnostics = languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnostics.stream().anyMatch(diag -> diag.getMessage().contains("Non-parseable POM")));
	}

	@Test
	public void testMissingArtifactIdError()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-without-artifactId.xml", languageService);
		languageService.didOpen(document);

		List<Diagnostic>diagnostics = languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {});
		System.out.println(diagnostics);
		assertTrue(diagnostics.stream().map(Diagnostic::getMessage)
				.anyMatch(message -> message.contains("artifactId")));
		// simulate an edit
		TextDocument textDocument = document.getTextDocument();
		textDocument.setText(textDocument.getText().replace("</project>", "<artifactId>a</artifactId></project>"));
		textDocument.setVersion(textDocument.getVersion() + 1);
		document = DOMParser.getInstance().parse(textDocument, languageService.getResolverExtensionManager());
		languageService.didOpen(document);
		
		assertEquals(Collections.emptyList(), languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {}));
	}

	@Test
	public void testSystemPathDiagnosticBug()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-environment-variable-property.xml", languageService);
		languageService.didOpen(document);
		
		List<Diagnostic> diagnostics = languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnostics.stream().anyMatch(diag -> diag.getMessage().contains("${env")));
	}

	@Test
	public void testEnvironmentVariablePropertyHover()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-environment-variable-property.xml", languageService);
		languageService.didOpen(document);
		
		String hoverContents = languageService.doHover(document,
				new Position(16, 18), new SharedSettings()).getContents().getRight().getValue();
		// We can't test the value of an environment variable as it is platform-dependent
		assertNotNull(hoverContents);
	}

	@Test
	public void testCompletionEnvironmentVariableProperty()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-environment-variable-property.xml", languageService);
		languageService.didOpen(document);
		
		List<CompletionItem> completions = languageService.doComplete(document, new Position(16, 49), new SharedSettings()).getItems();
		assertTrue(completions.stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
				.anyMatch("${env.PATH}"::equals));
	}

	@Test
	@Timeout(15000)
	public void testCompleteScope() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-scope.xml", languageService);
		languageService.didOpen(document);
		
		assertTrue(languageService.doComplete(document, new Position(0, 7), new SharedSettings()).getItems().stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
				.anyMatch("compile"::equals));
		assertTrue(languageService.doComplete(document, new Position(1, 7), new SharedSettings()).getItems().stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
				.anyMatch("compile</scope>"::equals));
	}

	@Test
	@Timeout(15000)
	public void testCompletePhase() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-phase.xml", languageService);
		languageService.didOpen(document);
		
		assertTrue(languageService.doComplete(document, new Position(0, 7), new SharedSettings()).getItems().stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
				.anyMatch("generate-resources"::equals));
		assertTrue(languageService.doComplete(document, new Position(1, 7), new SharedSettings()).getItems().stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
				.anyMatch("generate-resources</phase>"::equals));
	}

	@Test
 	public void testPropertyHover() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-properties.xml", languageService);
		languageService.didOpen(document);
		
		Hover hover = languageService.doHover(document, new Position(15, 20), new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("$")));

 		hover = languageService.doHover(document, new Position(15, 40), new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("0.0.1-SNAPSHOT")));

 		hover = languageService.doHover(document, new Position(15, 13), new SharedSettings());
 		assertNull(hover);
	}

	@Test
 	public void testPropertyDefinitionSameDocument() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-properties-for-definition.xml", languageService);
		languageService.didOpen(document);
		
		Position pos = new Position(14, 22);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});

		DOMDocument targetDocument = document;
		DOMNode propertyNode = DOMUtils.findNodesByLocalName(targetDocument, "myProperty").stream().filter(node -> node.getParentElement().getLocalName().equals("properties")).collect(Collectors.toList()).get(0);;
		Range expectedTargetRange = XMLPositionUtility.createRange(propertyNode);
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetRange().equals(expectedTargetRange)));
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetSelectionRange().equals(expectedTargetRange)));
	}

	@Test
 	public void testMultiplePropertyHover() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-multiple-properties.xml", languageService);
		languageService.didOpen(document);
		
		Position pos = new Position(16, 12);
		Hover hover = languageService.doHover(document,pos, new SharedSettings());
		Range firstHoverRange = hover.getRange();
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("$")));

 		pos = new Position(16, 21);
 		hover = languageService.doHover(document,pos, new SharedSettings());
 		Range secondHoverRange = hover.getRange();
  		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("test")));
  		assertFalse(firstHoverRange.equals(secondHoverRange));
	}

	@Test
	public void testMultiplePropertyDefinitionRangeSameTag()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-multiple-properties.xml", languageService);
		languageService.didOpen(document);
		
		Position pos = new Position(16, 12);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});

		assertTrue(definitionLinks.size() == 1);
		assertTrue(definitionLinks.size() > 0);
		Range firstDefinitionRange = definitionLinks.get(0).getOriginSelectionRange();

		pos = new Position(16, 21);
		definitionLinks = languageService.findDefinition(document, pos, () -> {
		});
		assertTrue(definitionLinks.size() > 0);
		Range secondDefinitionRange = definitionLinks.get(0).getOriginSelectionRange();

		assertFalse(firstDefinitionRange.equals(secondDefinitionRange));

		List<DOMNode> nameNodes = DOMUtils.findNodesByLocalName(document, "name");
		assertTrue(nameNodes.size() == 1);
		Range parentNodeRange = XMLPositionUtility.createRange(nameNodes.get(0));
		assertFalse(firstDefinitionRange.equals(parentNodeRange));
		assertFalse(secondDefinitionRange.equals(parentNodeRange));
	}

	@Test
 	public void testPropertyDefinitionSameDocumentBug() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-definition-wrong-tag-bug.xml", languageService);
		languageService.didOpen(document);
		
		Position pos = new Position(22, 40);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});

		DOMDocument targetDocument = document;

		DOMNode propertyNode = DOMUtils.findNodesByLocalName(targetDocument, "lemminx.maven.indexDirectory").stream().filter(node -> node.getParentElement().getLocalName().equals("properties")).collect(Collectors.toList()).get(0);
		assertTrue(propertyNode.getParentNode().getLocalName().equals("properties"));
		Range expectedTargetRange = XMLPositionUtility.createRange(propertyNode);
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetRange().equals(expectedTargetRange)));
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetSelectionRange().equals(expectedTargetRange)));
	}

	@Test
 	public void testPropertyDefinitionParentDocument() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-properties-in-parent-for-definition.xml", languageService);
		languageService.didOpen(document);
		
		Position pos = new Position(23, 16);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});

		//Verify the LocationLink points to the right file and node
		DOMDocument targetDocument = createDOMDocument("/pom-with-properties-for-definition.xml", languageService);
		languageService.didOpen(document);
		
		DOMNode propertyNode = DOMUtils.findNodesByLocalName(targetDocument, "myProperty").stream().filter(node -> node.getParentElement().getLocalName().equals("properties")).collect(Collectors.toList()).get(0);;
		Range expectedTargetRange = XMLPositionUtility.createRange(propertyNode);
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetRange().equals(expectedTargetRange)));
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetSelectionRange().equals(expectedTargetRange)));
	}

	@Test
	public void testModuleDefinition() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-module-definition.xml", languageService);
		languageService.didOpen(document);
		
		Position pos = new Position(11, 5);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});

		DOMDocument targetDocument = createDOMDocument("/multi-module/pom.xml", languageService);
		languageService.didOpen(document);
		
		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
	}

	@Test
	public void testModules() throws IOException, URISyntaxException {
		DOMDocument documentA = createDOMDocument("/modules/module-a-pom.xml", languageService);
		languageService.didOpen(documentA);
		
		List<Diagnostic> diagnosticsA = languageService.doDiagnostics(
				documentA, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnosticsA.stream()
				.anyMatch(diag -> (diag.getMessage().contains("ModuleA") || diag.getMessage().contains("ModuleB"))));

		DOMDocument documentB = createDOMDocument("/modules/dependent/module-b-pom.xml", languageService);
		languageService.didOpen(documentB);
		
		List<Diagnostic> diagnosticsB = languageService.doDiagnostics(
				documentB, new XMLValidationSettings(), Map.of(), () -> {});
		// As there is not workspace folders initialized, there is the error
		// "Could not find artifact org.test.modules:ModuleA:jar:0.0.1-SNAPSHOT
		assertArrayEquals(new Diagnostic[] { d(9, 4, 13, 17, null,
				"", "xml", DiagnosticSeverity.Error), //
		}, diagnosticsB.stream().filter(d -> {d.setMessage("");	return true;})
				.toList().toArray(new Diagnostic[diagnosticsB.size()]));
	}
	
	@Test
	public void testModulesCompletionInDependency() throws IOException, URISyntaxException, InterruptedException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
		
		DOMDocument documentA = createDOMDocument("/modules/module-a-pom.xml", languageService);
		languageService.didOpen(documentA);
		
		List<Diagnostic> diagnosticsA = languageService.doDiagnostics(
				documentA, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnosticsA.stream().anyMatch(diag -> (diag.getMessage().contains("ModuleA") || diag.getMessage().contains("ModuleB"))));

		DOMDocument documentB = createDOMDocument("/modules/dependent/module-b-pom.xml", languageService);
		languageService.didOpen(documentB);
		
		List<Diagnostic> diagnosticsB = languageService.doDiagnostics(
				documentB, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnosticsB.stream().anyMatch(diag -> (diag.getMessage().contains("ModuleA") || diag.getMessage().contains("ModuleB"))));

		// The items collected from Workspace as well as from Maven Search API cannot be 
		// immediately obtained due to the "lazy: loading, so, we need to wait until all 
		// the required data  received and ready for use
		
		// in <dependency />
		// for group ID
		List<CompletionItem> completions = null;
		int triesLeft = 15; // given a 1-second `sleep` between the retries this gives >15 seconds overall timeout
		boolean conditionMet = false;
		do {
			completions = languageService.doComplete(
					documentB, new Position(10, 15), new SharedSettings())
 				.getItems();
			Thread.sleep(1000);
			conditionMet = completions.stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
					.anyMatch("org.test.modules"::equals);
		} while (!conditionMet && triesLeft-- > 0);
		
		// for artifact ID:
		triesLeft = 15; // given a 1-second `sleep` between the retries this gives >15 seconds overall timeout
		conditionMet = false;
		do {
			completions = languageService.doComplete(
					documentB, new Position(11, 18), new SharedSettings())
 				.getItems();
			Thread.sleep(1000);
			conditionMet = completions.stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
					.anyMatch("ModuleA"::equals);
		} while (!conditionMet && triesLeft-- > 0);

		// for versions
		triesLeft = 15; // given a 1-second `sleep` between the retries this gives >15 seconds overall timeout
		conditionMet = false;
		do {
			completions = languageService.doComplete(
					documentB, new Position(12, 15), new SharedSettings())
 				.getItems();
			Thread.sleep(1000);
			conditionMet = completions.stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
					.anyMatch("0.0.1-SNAPSHOT"::equals);
		} while (!conditionMet && triesLeft-- > 0);
	}

	@Test
	public void testModulesCompletionInParent() throws IOException, URISyntaxException, InterruptedException {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument documentA = createDOMDocument("/modules/module-a-pom.xml", languageService);
		languageService.didOpen(documentA);

		List<Diagnostic> diagnosticsA = languageService.doDiagnostics(
				documentA, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnosticsA.stream().anyMatch(diag -> (diag.getMessage().contains("ModuleA") || diag.getMessage().contains("ModuleB"))));

		DOMDocument documentC = createDOMDocument("/modules/dependent/module-c-pom.xml", languageService);
		languageService.didOpen(documentC);

		List<Diagnostic> diagnosticsC = languageService.doDiagnostics(
				documentC, new XMLValidationSettings(), Map.of(), () -> {});
		assertFalse(diagnosticsC.stream().anyMatch(diag -> (diag.getMessage().contains("ModuleA") || diag.getMessage().contains("ModuleB"))));

		// in <parent />
		// for group ID
		List<CompletionItem> completions = null;
		int triesLeft = 15; // given a 1-second `sleep` between the retries this gives >15 seconds overall timeout
		boolean conditionMet = false;
		do {
			completions = languageService.doComplete(
					documentC, new Position(9, 13), new SharedSettings())
 				.getItems();
			Thread.sleep(1000);
			conditionMet = completions.stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
					.anyMatch("org.test.modules"::equals);
		} while (!conditionMet && triesLeft-- > 0);
		
		// for artifact ID:
		triesLeft = 15; // given a 1-second `sleep` between the retries this gives >15 seconds overall timeout
		conditionMet = false;
		do {
			completions = languageService.doComplete(
					documentC,  new Position(10, 16), new SharedSettings())
 				.getItems();
			Thread.sleep(1000);
			conditionMet = completions.stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
					.anyMatch("ModuleA"::equals);
		} while (!conditionMet && triesLeft-- > 0);

		// for versions
		triesLeft = 15; // given a 1-second `sleep` between the retries this gives >15 seconds overall timeout
		conditionMet = false;
		do {
			completions = languageService.doComplete(
					documentC, new Position(11, 13), new SharedSettings())
 				.getItems();
			Thread.sleep(1000);
			conditionMet = completions.stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText)
					.anyMatch("0.0.1-SNAPSHOT"::equals);
		} while (!conditionMet && triesLeft-- > 0);
	}
	
	@Test
	public void testModulesDefinitionInDependency() throws IOException, URISyntaxException {
		// We need the targetDocument to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument document = createDOMDocument("/modules/dependent/module-b-pom.xml", languageService);
		languageService.didOpen(document);

		Position pos = new Position(11, 18);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});
		assertFalse(definitionLinks.isEmpty());

		DOMDocument targetDocument = createDOMDocument("/modules/module-a-pom.xml", languageService);
		languageService.didOpen(targetDocument);

		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
	}

	@Test
	public void testModulesDefinitionInParent() throws IOException, URISyntaxException {
		// We need the targetDocument to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument document = createDOMDocument("/modules/dependent/module-c-pom.xml", languageService);
		languageService.didOpen(document);

		Position pos = new Position(10, 16);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});
		assertFalse(definitionLinks.isEmpty());

		DOMDocument targetDocument = createDOMDocument("/modules/module-a-pom.xml", languageService);
		languageService.didOpen(targetDocument);

		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
	}

	@Test
 	public void testModulesHoverInDependency() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument document = createDOMDocument("/modules/dependent/module-b-pom.xml", languageService);
		languageService.didOpen(document);

		
		// Hover over groupID text
		Position pos = new Position(10, 16);	// <groupId>o|rg.test.modules</groupId>
		Hover hover = languageService.doHover(document,pos, new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("ModuleA")));
		
		// Hover over artifactID text
		pos = new Position(11, 19); 	// <artifactId>M|oduleA</artifactId>
		hover = languageService.doHover(document, pos, new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("ModuleA")));

		// Hover over version text
 		pos = new Position(12, 16);		// <version>0|.0.1-SNAPSHOT</version>
		hover = languageService.doHover(document, pos, new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("ModuleA")));
	}

	@Test
 	public void testModulesHoverInParent() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/modules").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		DOMDocument document = createDOMDocument("/modules/dependent/module-c-pom.xml", languageService);
		languageService.didOpen(document);

		// Hover over groupID text
		Position pos = new Position(9, 14);	// <groupId>o|rg.test.modules</groupId>
		Hover hover = languageService.doHover(document, pos, new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("ModuleA")));
		
		// Hover over artifactID text
		pos = new Position(10, 17); 	// <artifactId>M|oduleA</artifactId>
		hover = languageService.doHover(document, pos, new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("ModuleA")));

		// Hover over version text
 		pos = new Position(11, 14);		// <version>0|.0.1-SNAPSHOT</version>
		hover = languageService.doHover(document, pos, new SharedSettings());
 		assertTrue((((MarkupContent) hover.getContents().getRight()).getValue().contains("ModuleA")));
	}
	
	@Test
	public void testParentDefinitionWithRelativePath() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-with-properties-in-parent-for-definition.xml", languageService);
		languageService.didOpen(document);

		Position pos = new Position(6, 9);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});

		DOMDocument targetDocument = createDOMDocument("/pom-with-properties-for-definition.xml", languageService);
		languageService.didOpen(targetDocument);

		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
	}

	@Test
	public void testParentDefinitionWithoutRelativePath() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/multi-module/folder1/pom.xml", languageService);
		languageService.didOpen(document);

		Position pos = new Position(6, 9);
		List<? extends LocationLink> definitionLinks = languageService.findDefinition(document, pos, () -> {
		});

		DOMDocument targetDocument = createDOMDocument("/multi-module/pom.xml", languageService);
		languageService.didOpen(targetDocument);

		assertTrue(definitionLinks.stream().anyMatch(link -> link.getTargetUri().equals(targetDocument.getDocumentURI())));
	}

	@Test
	public void testBOMDependency() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-bom.xml", languageService);
		languageService.didOpen(document);

		assertEquals(Collections.emptyList(), languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {}));
	}

	@Test
	public void testCompleteSNAPSHOT() throws Exception {
		DOMDocument document = createDOMDocument("/pom-version.xml", languageService);
		languageService.didOpen(document);
		
		Optional<TextEdit> edit = languageService.doComplete(document, new Position(0, 11), new SharedSettings()).getItems().stream().map(CompletionItem::getTextEdit).map(Either::getLeft).findFirst();
		assertTrue(edit.isPresent());
		assertEquals("-SNAPSHOT", edit.get().getNewText());
		assertEquals(new Range(new Position(0, 9), new Position(0, 11)), edit.get().getRange());
	}

	@Test
	public void testResolveParentFromCentralWhenAnotherRepoIsDeclared() throws Exception {
		DOMDocument document = createDOMDocument("/it1/pom.xml", languageService);
		languageService.didOpen(document);
		
		assertArrayEquals(new Diagnostic[] { //
				// org.sonatype.forge:forge-parent:jar:10 failed to transfer from https://download.eclipse.org/eclipse/updates/4.16/ 
				// during a previous attempt. This failure was cached in the local repository and resolution is not reattempted 
				// until the update interval of platform has elapsed or updates are forced. Original error: 
				// Could not transfer artifact org.sonatype.forge:forge-parent:jar:10 from/to platform (https://download.eclipse.org/eclipse/updates/4.16/): Cannot access https://download.eclipse.org/eclipse/updates/4.16/ with type p2 using the available connector factories: AetherRepositoryConnectorFactory"
				d(27, 0, 31, 13, null,	"", "xml", DiagnosticSeverity.Error), //
				// org.sonatype.forge:forge-parent:jar:10 was not found in https://repo.maven.apache.org/maven2 
				// during a previous attempt. This failure was cached in the local repository and resolution is 
				// not reattempted until the update interval of central has elapsed or updates are forced
				d(27, 0, 31, 13, null,	"", "xml", DiagnosticSeverity.Error) //
				},
		languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {})
			.stream()
			.filter(diag -> {
				// as message is to complex, we don't compare them
				diag.setMessage("");
				return diag.getSeverity() == DiagnosticSeverity.Error;
			})
			.toArray(Diagnostic[]::new));
	}

	@Test
	public void testSystemPath() throws Exception {
		DOMDocument document = createDOMDocument("/pom-systemPath.xml", languageService);
		languageService.didOpen(document);

		List<Diagnostic> diagnostics = languageService.doDiagnostics(
				document, new XMLValidationSettings(), Map.of(), () -> {});
		// 'dependencies.dependency.systemPath' for a:a:jar should not point at files
		// within the project directory, ${basedir} will be unresolvable by dependent
		// projects
		// 'dependencies.dependency.systemPath' for a:a:jar must specify an absolute
		// path but is ${basedir}
		assertTrue(
				diagnostics.stream().anyMatch(diag -> (diag.getMessage().contains("dependencies.dependency.systemPath")
						&& r(14, 17, 14, 27).equals(diag.getRange()))));
		languageService.dispose();
	}

	@Test
	public void testPluginInProfileOnly() throws Exception {
		DOMDocument document = createDOMDocument("/pom-gpg.xml", languageService);
		languageService.didOpen(document);

		Optional<Diagnostic> diagnostics = languageService.doDiagnostics(
				document, new XMLValidationSettings(), Map.of(), () -> {}).stream().filter(diag -> diag.getSeverity() == DiagnosticSeverity.Warning).findAny();
		assertTrue(diagnostics.isEmpty(), () -> diagnostics.map(Object::toString).get());
	}

	@Test
	public void testParentAsWorkspaceFolderInInitializeParam() throws Exception {
		InitializeParams params = new InitializeParams();
		String childFolder = "/parentAsSiblingProjectWithoutRelativePath/child";
		String parentFolder = "/parentAsSiblingProjectWithoutRelativePath/parent";
		
		URI childFolderUri = MavenLemminxTestsUtils.class.getResource(childFolder).toURI();
		URI parentFolderUri = MavenLemminxTestsUtils.class.getResource(parentFolder).toURI();

		params.setWorkspaceFolders(List.of(
				new WorkspaceFolder(childFolderUri.toString(), new File(childFolderUri.getPath()).getName()),
				new WorkspaceFolder(parentFolderUri.toString(), new File(parentFolderUri.getPath()).getName())));
		languageService.initializeParams(params);

		DOMDocument document = createDOMDocument(childFolder + "/pom.xml", languageService);
		languageService.didOpen(document);

		Optional<Diagnostic> diagnostics = languageService.doDiagnostics(
				document, new XMLValidationSettings(), Map.of(), () -> {}).stream().findAny();
		assertTrue(diagnostics.isEmpty(), () -> diagnostics.map(Object::toString).get());
	}
}