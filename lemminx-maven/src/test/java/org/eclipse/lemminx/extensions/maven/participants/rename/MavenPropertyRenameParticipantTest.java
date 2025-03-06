/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.rename;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.PROPERTIES_ELT;
import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.eclipse.lemminx.utils.TextEditUtils.creatTextDocumentEdit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.MavenWorkspaceService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.services.extensions.IWorkspaceServiceParticipant;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class MavenPropertyRenameParticipantTest {
	private MavenLanguageService languageService = new MavenLanguageService();

	@Test
	public void testRenameMavenProperty() throws Exception {
		String propertyName = "myPropertyGroupId"; 
		String newPropertyName = "new-group-id"; 
		DOMDocument xmlDocument = createDOMDocument("/property-refactoring/pom-with-property.xml", languageService);
		languageService.didOpen(xmlDocument);
		
		Optional<DOMElement> properties = DOMUtils.findChildElement(xmlDocument.getDocumentElement(), PROPERTIES_ELT);
		assertTrue(properties.isPresent(), "'<properties>'  Element doesn't exist!");

		Optional<DOMElement> property = DOMUtils.findChildElement(properties.get(), propertyName);
		assertTrue(properties.isPresent(), "'<" + propertyName + ">' Element doesn't exist!");

		DOMElement propertyElement = property.get();

		// Save property definition ranges for testing
		int startTagOpenOffset = propertyElement.getStartTagOpenOffset() + 1;
		int startTagCloseOffset = propertyElement.getStartTagCloseOffset();
		int endTagOpenOffset = propertyElement.getEndTagOpenOffset() + 2;
		int endTagCloseOffset = propertyElement.getEndTagCloseOffset();

		Range expectedStartTagRange = 
				new Range(xmlDocument.positionAt(startTagOpenOffset), xmlDocument.positionAt(startTagCloseOffset));
		Range expectedEndTagRange = 
				new Range(xmlDocument.positionAt(endTagOpenOffset), xmlDocument.positionAt(endTagCloseOffset));
		List<Range> propertyUseRanges =collectMavenPropertyUsages(xmlDocument, propertyName);
		assertFalse(propertyUseRanges.isEmpty(), "Property use entries not found!");
		Range expectedFirstUseRange = propertyUseRanges.get(0);

		// Expected changes:
		// - Start and and tags of maven property definition 
		// - one use of maven property
		List<TextEdit> expectedTextEdits = new ArrayList<>();
		expectedTextEdits.add(new TextEdit(expectedStartTagRange, newPropertyName));
		expectedTextEdits.add(new TextEdit(expectedEndTagRange, newPropertyName));
		propertyUseRanges.stream().map(r -> new TextEdit(r,newPropertyName))
			.forEach(expectedTextEdits::add);
		// final List<Either<TextDocumentEdit, ResourceOperation>> documentChanges
		WorkspaceEdit expectedRenameResult = new WorkspaceEdit(Arrays.asList(
				Either.forLeft(creatTextDocumentEdit(xmlDocument, expectedTextEdits))));

		// Test renaming start tag of maven property definition
		Position startTagMiddle = xmlDocument.positionAt((startTagOpenOffset + startTagCloseOffset) / 2);

		Either<Range, PrepareRenameResult> prepareResult = 
				languageService.prepareRename(xmlDocument, startTagMiddle, () -> {});
		assertNotNull(prepareResult, "Prepare Result is null!");
		assertNotNull(prepareResult.getLeft(), "Prepare Result Range is null!");

		Range prepareResultRange = prepareResult.getLeft();
		assertEquals(expectedStartTagRange, prepareResultRange);

		WorkspaceEdit renameReult = languageService.doRename(xmlDocument, startTagMiddle, newPropertyName, () -> {});
		assertNotNull(renameReult, "Prepare Result is null!");
		assertNotNull(renameReult.getDocumentChanges(), "Rename result document changes is null!");
		assertEquals(expectedRenameResult, renameReult);

		// Test renaming end tag of maven property definition
		Position endTagMiddle = xmlDocument.positionAt((endTagOpenOffset + endTagCloseOffset) / 2);

		prepareResult = languageService.prepareRename(xmlDocument, endTagMiddle, () -> {});
		assertNotNull(prepareResult, "Prepare Result is null!");
		assertNotNull(prepareResult.getLeft(), "Prepare Result Range is null!");

		prepareResultRange = prepareResult.getLeft();
		assertEquals(expectedEndTagRange, prepareResultRange);

		renameReult = languageService.doRename(xmlDocument, endTagMiddle, newPropertyName, () -> {});
		assertNotNull(renameReult, "Prepare Result is null!");
		assertNotNull(renameReult.getDocumentChanges(), "Rename result document changes is null!");
		assertEquals(expectedRenameResult, renameReult);

		// Test renaming maven property from first use
		Range firstUse = propertyUseRanges.get(0);
		Position firstUseMiddle = new Position(
				(firstUse.getStart().getLine() + firstUse.getEnd().getLine()) / 2,
				(firstUse.getStart().getCharacter() + firstUse.getEnd().getCharacter()) / 2);				

		prepareResult = languageService.prepareRename(xmlDocument, firstUseMiddle, () -> {});
		assertNotNull(prepareResult, "Prepare Result is null!");
		assertNotNull(prepareResult.getLeft(), "Prepare Result Range is null!");

		prepareResultRange = prepareResult.getLeft();
		assertEquals(expectedFirstUseRange, prepareResultRange);

		renameReult = languageService.doRename(xmlDocument, firstUseMiddle, newPropertyName, () -> {});
		assertNotNull(renameReult, "Prepare Result is null!");
		assertNotNull(renameReult.getDocumentChanges(), "Rename result document changes is null!");
		assertEquals(expectedRenameResult, renameReult);
	}
	
	@Test
	public void testRenameMavenPropertyWithChildren() throws Exception {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = languageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);

		URI folderUri = getClass().getResource("/property-refactoring/child").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));

		String propertyName = "test-version";
		String newPropertyName = "new-test-version"; 
		DOMDocument xmlDocument = createDOMDocument("/property-refactoring/child/parent/pom.xml", languageService);
		languageService.didOpen(xmlDocument);

		assertNotNull(xmlDocument, "Parent document not found!");

		Optional<DOMElement> properties = DOMUtils.findChildElement(xmlDocument.getDocumentElement(), PROPERTIES_ELT);
		assertTrue(properties.isPresent(), "'<properties>'  Element doesn't exist!");

		Optional<DOMElement> property = DOMUtils.findChildElement(properties.get(), propertyName);
		assertTrue(properties.isPresent(), "'<" + propertyName + ">' Element doesn't exist!");

		DOMElement propertyElement = property.get();

		// Save property use ranges for parent (this) document
		int startTagOpenOffset = propertyElement.getStartTagOpenOffset() + 1;
		int startTagCloseOffset = propertyElement.getStartTagCloseOffset();
		int endTagOpenOffset = propertyElement.getEndTagOpenOffset() + 2;
		int endTagCloseOffset = propertyElement.getEndTagCloseOffset();

		Range expectedStartTagRange = 
				new Range(xmlDocument.positionAt(startTagOpenOffset), xmlDocument.positionAt(startTagCloseOffset));
		Range expectedEndTagRange = 
				new Range(xmlDocument.positionAt(endTagOpenOffset), xmlDocument.positionAt(endTagCloseOffset));
		List<Range> propertyUseRanges =collectMavenPropertyUsages(xmlDocument, propertyName);
		assertFalse(propertyUseRanges.isEmpty(), "Property use entries not found!");
		Range expectedFirstUseRange = propertyUseRanges.get(0);

		// Save property use ranges for child document
		DOMDocument childXmlDocument = createDOMDocument("/property-refactoring/child/pom.xml", languageService);
		languageService.didOpen(xmlDocument);

		assertNotNull(childXmlDocument, "Child document not found!");
		List<Range> childPropertyUseRanges =collectMavenPropertyUsages(childXmlDocument, propertyName);

		// Expected changes:
		// - Start and and tags of maven property definition 
		// - one use of maven property
		List<TextEdit> expectedParentTextEdits = new ArrayList<>();
		expectedParentTextEdits.add(new TextEdit(expectedStartTagRange, newPropertyName));
		expectedParentTextEdits.add(new TextEdit(expectedEndTagRange, newPropertyName));
		propertyUseRanges.stream().map(r -> new TextEdit(r,newPropertyName))
			.forEach(expectedParentTextEdits::add);

		List<TextEdit> expectedChildTextEdits = new ArrayList<>();
		childPropertyUseRanges.stream().map(r -> new TextEdit(r,newPropertyName))
			.forEach(expectedChildTextEdits::add);

		// final List<Either<TextDocumentEdit, ResourceOperation>> documentChanges
		WorkspaceEdit expectedRenameResult = new WorkspaceEdit(Arrays.asList(
				Either.forLeft(creatTextDocumentEdit(xmlDocument, expectedParentTextEdits)),
				Either.forLeft(creatTextDocumentEdit(childXmlDocument, expectedChildTextEdits))));

		// Test renaming start tag of maven property definition
		Position startTagMiddle = xmlDocument.positionAt((startTagOpenOffset + startTagCloseOffset) / 2);

		Either<Range, PrepareRenameResult> prepareResult = 
				languageService.prepareRename(xmlDocument, startTagMiddle, () -> {});
		assertNotNull(prepareResult, "Prepare Result is null!");
		assertNotNull(prepareResult.getLeft(), "Prepare Result Range is null!");

		Range prepareResultRange = prepareResult.getLeft();
		assertEquals(expectedStartTagRange, prepareResultRange);

		WorkspaceEdit renameReult = languageService.doRename(xmlDocument, startTagMiddle, newPropertyName, () -> {});
		assertNotNull(renameReult, "Prepare Result is null!");
		assertNotNull(renameReult.getDocumentChanges(), "Rename result document changes is null!");
		assertEquals(expectedRenameResult, renameReult);

		// Test renaming end tag of maven property definition
		Position endTagMiddle = xmlDocument.positionAt((endTagOpenOffset + endTagCloseOffset) / 2);

		prepareResult = languageService.prepareRename(xmlDocument, endTagMiddle, () -> {});
		assertNotNull(prepareResult, "Prepare Result is null!");
		assertNotNull(prepareResult.getLeft(), "Prepare Result Range is null!");

		prepareResultRange = prepareResult.getLeft();
		assertEquals(expectedEndTagRange, prepareResultRange);

		renameReult = languageService.doRename(xmlDocument, endTagMiddle, newPropertyName, () -> {});
		assertNotNull(renameReult, "Prepare Result is null!");
		assertNotNull(renameReult.getDocumentChanges(), "Rename result document changes is null!");
		assertEquals(expectedRenameResult, renameReult);

		// Test renaming maven property from first use
		Range firstUse = propertyUseRanges.get(0);
		Position firstUseMiddle = new Position(
				(firstUse.getStart().getLine() + firstUse.getEnd().getLine()) / 2,
				(firstUse.getStart().getCharacter() + firstUse.getEnd().getCharacter()) / 2);				

		prepareResult = languageService.prepareRename(xmlDocument, firstUseMiddle, () -> {});
		assertNotNull(prepareResult, "Prepare Result is null!");
		assertNotNull(prepareResult.getLeft(), "Prepare Result Range is null!");

		prepareResultRange = prepareResult.getLeft();
		assertEquals(expectedFirstUseRange, prepareResultRange);

		renameReult = languageService.doRename(xmlDocument, firstUseMiddle, newPropertyName, () -> {});
		assertNotNull(renameReult, "Prepare Result is null!");
		assertNotNull(renameReult.getDocumentChanges(), "Rename result document changes is null!");
		assertEquals(expectedRenameResult, renameReult);
	}
	
	private static final List<Range> collectMavenPropertyUsages(DOMDocument xmlDocument, String propertyName) {
		String propertyUse = "${" + propertyName + "}";
		String text = xmlDocument.getText();
		TextDocument document = xmlDocument.getTextDocument();
		List<Range> propertyUseRanges = new ArrayList<>();
		int index = 0;
		for (index = text.indexOf(propertyUse); index != -1; index = text.indexOf(propertyUse, index + propertyUse.length())) {
			try {
				int propertyUseStart = index + "${".length();
				int propertyUseEnd = propertyUseStart + propertyName.length();
				Position positionStart = document.positionAt(propertyUseStart);
				Position positionEnd = document.positionAt(propertyUseEnd);
				propertyUseRanges.add(new Range(positionStart, positionEnd));
			} catch (BadLocationException e) {
				fail("Cannot find all property uses in test data", e);
			}
		}
		return propertyUseRanges;
	}
}