/*******************************************************************************
 * Copyright (c) 2019-2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants;

import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.lemminx.commons.DiagnosticUtils;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationSettings;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class LocalPluginTest {

	private XMLLanguageService languageService;

	private SharedSettings getMarkdownSharedSettings () {
		SharedSettings settings = new SharedSettings();
		HoverCapabilities hover = new HoverCapabilities();
		String capabilities[] = { MarkupKind.MARKDOWN };
		hover.setContentFormat(Arrays.asList(capabilities));

		settings.getHoverSettings().setCapabilities(hover);

		return settings;
	}

	@BeforeEach
	public void setUp() throws IOException {
		languageService = new MavenLanguageService();
	}

	@AfterEach
	public void tearDown() throws InterruptedException, ExecutionException {
		languageService.dispose();
		languageService = null;
	}

	// Completion related tests

	@Test
	public void testCompleteGoal() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doComplete(createDOMDocument("/pom-complete-plugin-goal.xml", languageService),
				new Position(18, 19), new SharedSettings())
			.getItems().stream().map(CompletionItem::getTextEdit).map(Either::getLeft).map(TextEdit::getNewText).anyMatch("test"::equals));
	}

	@Test
	public void testCompleteConfigurationParameters() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doComplete(createDOMDocument("/pom-complete-plugin-goal.xml", languageService),
				new Position(23, 7), new SharedSettings())
			.getItems().stream().map(CompletionItem::getLabel).anyMatch("failIfNoTests"::equals));
	}

	@Test
	public void testDuplicateCompletionConfigurationParameters() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		List<CompletionItem> completions = languageService.doComplete(createDOMDocument("/pom-complete-plugin-goal.xml", languageService),
				new Position(23, 7), new SharedSettings())
			.getItems();
		assertTrue(completions.stream().map(CompletionItem::getLabel).filter("failIfNoTests"::equals).count() == 1);
	}

	@Test
	public void testCompleteConfigurationParametersInTag() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doComplete(createDOMDocument("/pom-plugin-config-tag.xml", languageService),
				new Position(20, 9), new SharedSettings())
			.getItems().stream().map(CompletionItem::getLabel).anyMatch("failIfNoTests"::equals));
	}

	@Test
	public void testCompleteConfigurationParametersInTagDuplicates() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		List<CompletionItem> completions = languageService.doComplete(createDOMDocument("/pom-plugin-config-tag.xml", languageService),
				new Position(20, 9), new SharedSettings())
			.getItems();
		assertTrue(completions.stream().map(CompletionItem::getLabel).filter("failIfNoTests"::equals).count() == 1);
	}

	@Test
	public void testCompleteConfigurationParametersInTagAlias() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-plugin-config-tag-alias.xml", languageService);
		List<Diagnostic> diagnostics = languageService.doDiagnostics(document,
				new XMLValidationSettings(), Map.of(), () -> {});
		assertEquals(Optional.empty(), diagnostics.stream().filter(diagnostic -> diagnostic.getSeverity() == DiagnosticSeverity.Warning).findAny());
		assertTrue(languageService.doHover(document,
				new Position(20, 11), new SharedSettings()).getContents().getRight().getValue().contains("Specify the number of spaces each tab takes up in the source"));
	}

	@Test
	public void testCompleteConfigurationParametersDuplicates() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		List<CompletionItem> completions = languageService.doComplete(createDOMDocument("/pom-duplicate-configuration-completion.xml", languageService),
				new Position(15, 4), new SharedSettings())
			.getItems();
		assertTrue(completions.stream().map(CompletionItem::getLabel).filter("annotationProcessors"::equals).count() == 1);
	}

	// Test related to https://github.com/eclipse/lemminx-maven/issues/75
	@Test
	public void testCompleteConfigurationParametersMissingBug() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		Position pos = new Position(11, 23);
		List<CompletionItem> completions = languageService.doComplete(createDOMDocument("/pom-plugin-diagnostic-missing-groupid.xml", languageService),
				pos, new SharedSettings())
			.getItems();
		assertTrue(completions.stream().map(CompletionItem::getLabel).anyMatch("release"::equals));
	}

	// Test related to https://github.com/eclipse/lemminx-maven/issues/114
	@Test
	public void testFileCompletionDuplicatePrefix() throws IOException, URISyntaxException {
		Position pos = new Position(11, 26);
		List<CompletionItem> completions = languageService.doComplete(createDOMDocument("/pom-complete-file-path.xml", languageService),
				pos, new SharedSettings())
			.getItems();
		// Expected replace range is the whole text node range
		Range expectedReplaceRange = new Range(new Position(11, 10), new Position(11, 26));

		assertTrue(completions.stream().map(item -> item.getTextEdit().getLeft().getRange()).anyMatch(expectedReplaceRange::equals));
	}

	// Hover related tests

	@Test
 	public void testPluginConfigurationHover() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doHover(createDOMDocument("/pom-plugin-configuration-hover.xml", languageService),
				new Position(15, 6), new SharedSettings()).getContents().getRight().getValue().contains("cause a failure if there are no tests to run"));
	}

	@Test
 	public void testPluginNestedConfigurationHover() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument doc = createDOMDocument("/pom-plugin-nested-configuration-hover.xml", languageService);
		//<compilerArguments> hover
		String hoverContents = languageService.doHover(doc,
				new Position(15, 8), getMarkdownSharedSettings()).getContents().getRight().getValue();
		assertTrue(hoverContents.contains("**Type:** List&lt;String&gt;"));
		assertTrue(hoverContents.contains("Sets the arguments to be passed to the compiler"));

		//<arg> hover, child of compilerArguments
		//Should have a different type, but sames description
		hoverContents = languageService.doHover(doc,
				new Position(16, 8), getMarkdownSharedSettings()).getContents().getRight().getValue();
		assertTrue(hoverContents.contains("**Type:** String"));
		assertTrue(hoverContents.contains("Sets the arguments to be passed to the compiler"));

		//<annotationProcessorPath> hover
		hoverContents = languageService.doHover(doc,
				new Position(20, 9), getMarkdownSharedSettings())
				.getContents().getRight().getValue();
		//annotationProcessorPath type should be a DependencyCoordinate and its description should be that of annotationsProcessorPath
		assertTrue(hoverContents.contains("org.apache.maven.plugin.compiler.DependencyCoordinate"));
		assertTrue(hoverContents.contains("Classpath elements to supply as annotation processor path."));


		//<groupId> hover
		hoverContents = languageService.doHover(doc,
				new Position(21, 9), getMarkdownSharedSettings()).getContents().getRight().getValue();
		//GroupId type should be a string and its description should be that of annotationsProcessorPath
		assertTrue(hoverContents.contains("**Type:** String"));
		assertTrue(hoverContents.contains("Classpath elements to supply as annotation processor path."));

	}

	@Test
 	public void testPluginNestedConfigurationCompletion() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument doc = createDOMDocument("/pom-plugin-nested-configuration-completion.xml", languageService);
		//<compilerArguments> completion
		Position pos = new Position(16, 5);
		List<CompletionItem> completions = languageService.doComplete(doc,
				pos , new SharedSettings())
			.getItems();
		assertTrue(completions.stream().map(CompletionItem::getLabel).anyMatch("compilerArg"::equals));


		//<groupId> completion
		pos = new Position(20, 6);
		completions = languageService.doComplete(doc,
				pos , new SharedSettings())
			.getItems();
		assertTrue(completions.stream().map(CompletionItem::getLabel).anyMatch("groupId"::equals));
		assertTrue(completions.stream().map(CompletionItem::getLabel).anyMatch("artifactId"::equals));
		assertTrue(completions.stream().map(CompletionItem::getLabel).anyMatch("version"::equals));
	}

	@Test
 	public void testPluginGoalHover() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		assertTrue(languageService.doHover(createDOMDocument("/pom-plugin-goal-hover.xml", languageService),
				new Position(18, 22), new SharedSettings()).getContents().getRight().getValue().contains("Run tests using Surefire."));
	}

	@Test
 	public void testPluginArtifactHover() throws IOException, InterruptedException, ExecutionException, URISyntaxException, TimeoutException {
		assertTrue(languageService.doHover(createDOMDocument("/pom-plugin-artifact-hover.xml", languageService),
				new Position(14, 18), new SharedSettings()).getContents().getRight().getValue().contains("Maven Surefire MOJO in maven-surefire-plugin"));
	}

	// Definition related tests

	@Test
 	public void testPluginArtifactDefinition() throws IOException, InterruptedException, ExecutionException, URISyntaxException, TimeoutException {
        // Find Definition links
		List<? extends LocationLink> definitions = languageService.findDefinition(createDOMDocument("/pom-plugin-artifact-hover.xml", languageService), new Position(14, 18), ()->{});
		definitions.stream().map(LocationLink::getTargetUri).forEach(u -> System.out.println("Definition Link: " + u));
		assertTrue(definitions.stream().map(LocationLink::getTargetUri).anyMatch(uri -> uri.endsWith(".pom") && uri.contains("maven-surefire-plugin")));
	}
	
	// Diagnostic related tests

	@Test
	@Timeout(30000)
	public void testPluginConfigurationDiagnostics() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-plugin-configuration-diagnostic.xml", languageService);
		assertTrue(languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {
				}).stream().map(diag -> DiagnosticUtils.getDiagnosticMessage(diag))
				.anyMatch(message -> message.contains("Invalid plugin configuration")));
		assertTrue(languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {}).size() == 2);
	}

	@Test
	public void testPluginGoalDiagnostics() throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-plugin-goal-diagnostic.xml", languageService);
		assertTrue(languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {
				}).stream().map(diag -> DiagnosticUtils.getDiagnosticMessage(diag))
				.anyMatch(message -> message.contains("Invalid goal for this plugin")));
		assertTrue(languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {}).size() == 2);
	}

	@Test
	public void testDiagnosticMissingGroupId() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-plugin-diagnostic-missing-groupid.xml", languageService);
		List<Diagnostic> diagnostics = languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {});
		assertTrue(diagnostics.size() == 0);
	}

	@Test
	public void testGoalDiagnosticsNoFalsePositives()
			throws IOException, InterruptedException, ExecutionException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-plugin-valid-goal-diagnostic.xml", languageService);
		assertTrue(languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {}).size() == 0);
	}

	@Test
	public void testParentPluginManagementResolved() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-pluginManagement-child.xml", languageService);
		assertEquals(Collections.emptyList(), languageService.doDiagnostics(document, new XMLValidationSettings(), Map.of(), () -> {}));
	}

	@Test
	public void testParentPluginManagementConfigurationInChildren() throws IOException, URISyntaxException {
		DOMDocument document = createDOMDocument("/pom-pluginManagement-child.xml", languageService);
		CompletionList completion = languageService.doComplete(document, new Position(20, 20) /*after <configuration>*/, new SharedSettings());
		assertTrue(completion.getItems().stream().map(CompletionItem::getLabel).anyMatch("argLine"::equals), completion::toString);
	}
}
