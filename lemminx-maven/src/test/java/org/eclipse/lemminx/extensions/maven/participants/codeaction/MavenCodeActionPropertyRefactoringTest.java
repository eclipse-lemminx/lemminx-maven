/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.codeaction;

import static org.eclipse.lemminx.XMLAssert.ca;
import static org.eclipse.lemminx.XMLAssert.te;
import static org.eclipse.lemminx.XMLAssert.teOp;
import static org.eclipse.lemminx.XMLAssert.testCodeActionsFor;
import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.LineIndentInfo;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.MavenWorkspaceService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.services.extensions.IWorkspaceServiceParticipant;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class MavenCodeActionPropertyRefactoringTest {
	private XMLLanguageService xmlLanguageService = new MavenLanguageService();
	private SharedSettings sharedSettings = new SharedSettings();

	//
	// Inline maven properties
	//
	
	@Test
	public void testCodeActionsInlineSinglePropertyUse() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/property-refactoring/pom-with-property.xml", xmlLanguageService);
		String text = xmlDocument.getText();
		TextDocument document = xmlDocument.getTextDocument();
		
		// ${myPropertyGroupId}
		String propertyUse = "${myPropertyGroupId}";
		int propertyUseStart = text.indexOf(propertyUse);
		int propertyUseEnd = propertyUseStart + propertyUse.length();

		Position positionStart = document.positionAt(propertyUseStart);
		Position positionEnd = document.positionAt(propertyUseEnd);
		
		CodeAction expectedCodeAction = ca(null, 
				teOp(xmlDocument.getDocumentURI(), positionStart.getLine(), positionStart.getCharacter(), 
						positionEnd.getLine(), positionEnd.getCharacter(), "my-property-group-value"));
		expectedCodeAction.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided
		
		// Test for expected code actions returned
		testCodeActionsFor(xmlDocument.getText(), null, new Range(positionStart, positionEnd), 
				(String) null, xmlDocument.getDocumentURI(),  sharedSettings, xmlLanguageService, -1, 
				expectedCodeAction);
	}

	@Test
	public void testCodeActionsInlineMultiplePropertyUses() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/property-refactoring/pom-with-multiple-property-uses.xml", xmlLanguageService);
		String text = xmlDocument.getText();
		TextDocument document = xmlDocument.getTextDocument();
		
		// ${myPropertyGroupId}
		String propertyUse = "${myPropertyGroupId}";
		List<Range> ranges = new ArrayList<>();

		int index = 0;
		for (index = text.indexOf(propertyUse); index != -1; index = text.indexOf(propertyUse, index + propertyUse.length())) {
			try {
				int propertyUseStart = index;
				int propertyUseEnd = propertyUseStart + propertyUse.length();

				Position positionStart = document.positionAt(propertyUseStart);
				Position positionEnd = document.positionAt(propertyUseEnd);
				ranges.add(new Range(positionStart, positionEnd));
			} catch (BadLocationException e) {
				fail("Cannot find all property uses in test data", e);
			}
		}
		assertTrue(ranges.size() > 1, "There should be more than one prperty use");
		
		// Inline for the selected single property use 
		CodeAction expectedCodeAction_1 = ca(null, 
				teOp(xmlDocument.getDocumentURI(), 
						ranges.get(0).getStart().getLine(), ranges.get(0).getStart().getCharacter(), 
						ranges.get(0).getEnd().getLine(), ranges.get(0).getEnd().getCharacter(), 
						"my-property-group-value"));
		expectedCodeAction_1.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided
		
		// Inline for all the property uses in pom.xml 
		List<TextEdit> textEdits = ranges.stream().map(range -> 
			te(range.getStart().getLine(), range.getStart().getCharacter(), 
				range.getEnd().getLine(), range.getEnd().getCharacter(), 
				"my-property-group-value")).collect(Collectors.toList()) ;
		assertTrue(textEdits.size() == ranges.size(), "The TextEdits size should be equl to the size of ranges");
		CodeAction expectedCodeAction_2 = ca(null,  teOp(xmlDocument.getDocumentURI(), textEdits.toArray(new TextEdit[textEdits.size()])));
		expectedCodeAction_2.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided

		// Test for expected code actions returned
		testCodeActionsFor(xmlDocument.getText(), null, ranges.get(0), 
				(String) null, xmlDocument.getDocumentURI(),  sharedSettings, xmlLanguageService, -1, 
				expectedCodeAction_1, expectedCodeAction_2);
	}

	//
	// Extract maven properties
	//

	@Test
	public void testCodeActionsExtractSingleProperty() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/property-refactoring/pom-with-value.xml", xmlLanguageService);
		String text = xmlDocument.getText();
		TextDocument document = xmlDocument.getTextDocument();
		
		// <version>0.0.1</version>
		String valueUse = "<version>0.0.1</version>";

		// We'll try getting a code action from a cursor position 
		// somewhere in the middle of the value
		int valueStart = text.indexOf(valueUse);
		int valueEnd = valueStart + valueUse.length();
		Position valueStartPosition = document.positionAt(valueStart + "<version>".length());
		Position valueEndPosition = document.positionAt(valueEnd - "/<version>".length());

		Position position = document.positionAt((valueStart + valueEnd) / 2);
		
		// Code action for extracting a single value into a property

		// TextEdit to add a header properties
		int propertiesOffset = xmlDocument.getDocumentElement().getStartTagCloseOffset() + 1;
		Position propertiesPosition = xmlDocument.positionAt(propertiesOffset);
		LineIndentInfo indentInfo = xmlDocument.getLineIndentInfo(propertiesPosition.getLine());
		String lineDelimiter = indentInfo.getLineDelimiter();
		String indent = indentInfo.getWhitespacesIndent();
		
		StringBuilder propertiesValue = new StringBuilder(); 
		propertiesValue.append(lineDelimiter)
			.append(indent).append("<properties>").append(lineDelimiter)
			.append(indent).append(indent)
			.append("<test-1-version>0.0.1</test-1-version>").append(lineDelimiter)
			.append(indent).append("</properties>").append(lineDelimiter);
		
		List<TextEdit> textEdits = new ArrayList<>();
		textEdits.add(new TextEdit(new Range(propertiesPosition, propertiesPosition), propertiesValue.toString()));
		textEdits.add(new TextEdit(new Range(valueStartPosition, valueEndPosition), "${test-1-version}"));
		assertTrue(textEdits.size() == 2, "The TextEdits size should be 2");
		CodeAction expectedCodeAction = ca(null,  
				teOp(xmlDocument.getDocumentURI(), textEdits.toArray(new TextEdit[textEdits.size()])));
		expectedCodeAction.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided

		// Test for expected code actions returned
		testCodeActionsFor(xmlDocument.getText(), null, new Range(position, position), 
				(String) null, xmlDocument.getDocumentURI(),  sharedSettings, xmlLanguageService, -1, 
				expectedCodeAction);
	}	

	@Test
	public void testCodeActionsExtracMultiplePropertyUses() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/property-refactoring/pom-with-multiple-values.xml", xmlLanguageService);
		String text = xmlDocument.getText();
		TextDocument document = xmlDocument.getTextDocument();
		
		// <version>0.0.1</version>
		String valueUse = "<version>0.0.1</version>";
		List<Range> ranges = new ArrayList<>();

		int index = 0;
		for (index = text.indexOf(valueUse); index != -1; index = text.indexOf(valueUse, index + valueUse.length())) {
			try {
				int valueStart = index;
				int valueEnd = valueStart + valueUse.length();

				Position valueStartPosition = document.positionAt(valueStart + "<version>".length());
				Position valueEndPosition = document.positionAt(valueEnd - "/<version>".length());
				ranges.add(new Range(valueStartPosition, valueEndPosition));
			} catch (BadLocationException e) {
				fail("Cannot find all value uses in test data", e);
			}
		}
		assertTrue(ranges.size() > 1, "There should be more than one value use");

		// Code action for extracting a single value into a property

		// TextEdit to add a header properties
		int propertiesOffset = xmlDocument.getDocumentElement().getStartTagCloseOffset() + 1;
		Position propertiesPosition = xmlDocument.positionAt(propertiesOffset);
		LineIndentInfo indentInfo = xmlDocument.getLineIndentInfo(propertiesPosition.getLine());
		String lineDelimiter = indentInfo.getLineDelimiter();
		String indent = indentInfo.getWhitespacesIndent();
		
		StringBuilder propertiesValue = new StringBuilder(); 
		propertiesValue.append(lineDelimiter)
			.append(indent).append("<properties>").append(lineDelimiter)
			.append(indent).append(indent)
			.append("<test-1-version>0.0.1</test-1-version>").append(lineDelimiter)
			.append(indent).append("</properties>").append(lineDelimiter);
		
		List<TextEdit> singleTextEdits = new ArrayList<>();
		singleTextEdits.add(new TextEdit(new Range(propertiesPosition, propertiesPosition), propertiesValue.toString()));
		singleTextEdits.add(new TextEdit(ranges.get(0), "${test-1-version}"));
		assertTrue(singleTextEdits.size() == 2, "The TextEdits size should be 2");
		CodeAction expectedCodeAction_1 = ca(null,  
				teOp(xmlDocument.getDocumentURI(), singleTextEdits.toArray(new TextEdit[singleTextEdits.size()])));
		expectedCodeAction_1.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided
		
		// Code action for extracting all the value uses to a property 

		List<TextEdit> multipleTextEdits = new ArrayList<>();
		multipleTextEdits.add(new TextEdit(new Range(propertiesPosition, propertiesPosition), propertiesValue.toString()));
		multipleTextEdits.addAll(ranges.stream().map(range -> 
		te(range.getStart().getLine(), range.getStart().getCharacter(), 
			range.getEnd().getLine(), range.getEnd().getCharacter(), 
			"${test-1-version}")).collect(Collectors.toList())) ;
		assertTrue(multipleTextEdits.size() == ranges.size() + 1, "The TextEdits size should be equl to the size of ranges");
		CodeAction expectedCodeAction_2 = ca(null,  teOp(xmlDocument.getDocumentURI(), multipleTextEdits.toArray(new TextEdit[multipleTextEdits.size()])));
		expectedCodeAction_2.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided

		// Test for expected code actions returned
		testCodeActionsFor(xmlDocument.getText(), null, ranges.get(0), 
				(String) null, xmlDocument.getDocumentURI(),  sharedSettings, xmlLanguageService, -1, 
				expectedCodeAction_1, expectedCodeAction_2);
	}
	
	@Test
	public void testCodeActionsExtracPropertyUsesWithChildren() throws Exception {
		// We need the WORKSPACE projects to be placed to MavenProjectCache
		IWorkspaceServiceParticipant workspaceService = xmlLanguageService.getWorkspaceServiceParticipants().stream().filter(MavenWorkspaceService.class::isInstance).findAny().get();
		assertNotNull(workspaceService);
		
		URI folderUri = getClass().getResource("/property-refactoring/child").toURI();
		WorkspaceFolder wsFolder = new WorkspaceFolder(folderUri.toString(), new File(folderUri.getPath()).getName());

		// Add folders to MavenProjectCache
		workspaceService.didChangeWorkspaceFolders(
				new DidChangeWorkspaceFoldersParams(
						new WorkspaceFoldersChangeEvent (
								Arrays.asList(new WorkspaceFolder[] {wsFolder}), 
								Arrays.asList(new WorkspaceFolder[0]))));
		
		DOMDocument xmlDocument = createDOMDocument("/property-refactoring/child/pom.xml", xmlLanguageService);
		String text = xmlDocument.getText();
		TextDocument document = xmlDocument.getTextDocument();
		
		// <version>0.0.1</version>
		String valueUse = "<version>0.0.1-SNAPSHOT</version>";
		List<Range> ranges = new ArrayList<>();

		int index = 0;
		for (index = text.indexOf(valueUse); index != -1; index = text.indexOf(valueUse, index + valueUse.length())) {
			try {
				int valueStart = index;
				int valueEnd = valueStart + valueUse.length();

				Position valueStartPosition = document.positionAt(valueStart + "<version>".length());
				Position valueEndPosition = document.positionAt(valueEnd - "/<version>".length());
				ranges.add(new Range(valueStartPosition, valueEndPosition));
			} catch (BadLocationException e) {
				fail("Cannot find all value uses in test data", e);
			}
		}
		assertTrue(ranges.size() > 0, "There should be at least one value use");

		// Code action for extracting a single value into a property

		// TextEdit to add a header properties
		int propertiesOffset = xmlDocument.getDocumentElement().getStartTagCloseOffset() + 1;
		Position propertiesPosition = xmlDocument.positionAt(propertiesOffset);
		LineIndentInfo indentInfo = xmlDocument.getLineIndentInfo(propertiesPosition.getLine());
		String lineDelimiter = indentInfo.getLineDelimiter();
		String indent = indentInfo.getWhitespacesIndent();
		
		StringBuilder propertiesValue = new StringBuilder(); 
		propertiesValue.append(lineDelimiter)
			.append(indent).append("<properties>").append(lineDelimiter)
			.append(indent).append(indent)
			.append("<test-dependency-2-version>0.0.1-SNAPSHOT</test-dependency-2-version>").append(lineDelimiter)
			.append(indent).append("</properties>").append(lineDelimiter);
		
		List<TextEdit> singleTextEdits = new ArrayList<>();
		singleTextEdits.add(new TextEdit(new Range(propertiesPosition, propertiesPosition), propertiesValue.toString()));
		singleTextEdits.add(new TextEdit(ranges.get(0), "${test-dependency-2-version}"));
		assertTrue(singleTextEdits.size() == 2, "The TextEdits size should be 2");
		CodeAction expectedCodeAction_1 = ca(null,  
				teOp(xmlDocument.getDocumentURI(), singleTextEdits.toArray(new TextEdit[singleTextEdits.size()])));
		expectedCodeAction_1.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided

		// Code action for replacing a single value with an existing property

		List<TextEdit> singleReplaceTextEdits = new ArrayList<>();
		singleReplaceTextEdits.add(new TextEdit(ranges.get(0), "${test-version}"));
		assertTrue(singleReplaceTextEdits.size() == 1, "The TextEdits size should be 1");
		CodeAction expectedCodeAction_2 = ca(null,  
				teOp(xmlDocument.getDocumentURI(), singleReplaceTextEdits.toArray(new TextEdit[singleReplaceTextEdits.size()])));
		expectedCodeAction_2.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided
	
		// Code action for extracting a single value into a property in parent project

		DOMDocument parentXmlDocument = createDOMDocument("/property-refactoring/child/parent/pom.xml", xmlLanguageService);

		// TextEdit to add a header properties
		DOMElement properttiesElement = DOMUtils.findChildElement(parentXmlDocument.getDocumentElement(), "properties").orElse(null);
		assertNotNull(properttiesElement, "Properties element not found in parent document");
		
		propertiesOffset = properttiesElement.getStartTagCloseOffset() + 1;
		propertiesPosition = parentXmlDocument.positionAt(propertiesOffset);
		indentInfo = parentXmlDocument.getLineIndentInfo(propertiesPosition.getLine());
		lineDelimiter = indentInfo.getLineDelimiter();
		indent = indentInfo.getWhitespacesIndent();
		
		propertiesValue = new StringBuilder(); 
		propertiesValue.append(lineDelimiter).append(indent).append(indent)
			.append("<test-dependency-2-version>0.0.1-SNAPSHOT</test-dependency-2-version>");
		
		List<TextEdit> headerTextEdits = new ArrayList<>();
		headerTextEdits.add(new TextEdit(new Range(propertiesPosition, propertiesPosition), propertiesValue.toString()));
		assertTrue(headerTextEdits.size() == 1, "The TextEdits size should be 2");

		List<TextEdit> bodyTextEdits = new ArrayList<>();
		bodyTextEdits.add(new TextEdit(ranges.get(0), "${test-dependency-2-version}"));
		assertTrue(bodyTextEdits.size() == 1, "The TextEdits size should be 2");
		CodeAction expectedCodeAction_3 = ca(null,  
				teOp(parentXmlDocument.getDocumentURI(), headerTextEdits.toArray(new TextEdit[headerTextEdits.size()])),
				teOp(xmlDocument.getDocumentURI(), bodyTextEdits.toArray(new TextEdit[bodyTextEdits.size()])));
		expectedCodeAction_3.setDiagnostics(Collections.emptyList()); // No diagnostic should be provided

		// Test for expected code actions returned
		testCodeActionsFor(xmlDocument.getText(), null, ranges.get(0), 
				(String) null, xmlDocument.getDocumentURI(),  sharedSettings, xmlLanguageService, -1, 
				expectedCodeAction_1, expectedCodeAction_2, expectedCodeAction_3);
	}
}
