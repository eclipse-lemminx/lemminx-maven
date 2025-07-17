/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.codeaction;

import static org.eclipse.lemminx.XMLAssert.assertCodeActions;
import static org.eclipse.lemminx.XMLAssert.ca;
import static org.eclipse.lemminx.XMLAssert.d;
import static org.eclipse.lemminx.XMLAssert.teOp;
import static org.eclipse.lemminx.extensions.maven.participants.codeaction.MavenNoGrammarConstraintsCodeAction.XSI_VALUE_PATTERN;
import static org.eclipse.lemminx.extensions.maven.utils.MavenLemminxTestsUtils.createDOMDocument;
import static org.eclipse.lemminx.extensions.maven.utils.ParticipantUtils.getDocumentLineSeparator;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.lemminx.XMLAssert.SettingsSaveContext;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.extensions.contentmodel.participants.XMLSyntaxErrorCode;
import org.eclipse.lemminx.extensions.contentmodel.settings.ContentModelSettings;
import org.eclipse.lemminx.extensions.contentmodel.settings.XMLValidationRootSettings;
import org.eclipse.lemminx.extensions.maven.MavenLanguageService;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.eclipse.lemminx.extensions.maven.participants.diagnostics.MavenSyntaxErrorCode;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lemminx.utils.StringUtils;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.google.gson.Gson;

@ExtendWith(NoMavenCentralExtension.class)
public class MavenCodeActionParticipantTest {
	private static final String POM_FILE = "file:///pom.xml";
	
	private XMLLanguageService xmlLanguageService = new MavenLanguageService();
	private SharedSettings sharedSettings = new SharedSettings();

	// EDITOR_HINT_MISSING_SCHEMA == NoGrammarConstraints
	@Test
	public void testCodeActionsForNoGrammarConstraints() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/codeactions-test/pom-no-grammar-constraints.xml", xmlLanguageService);
		
		// Test diagnostic and code action for absent grammar constraints
		Diagnostic expectedDiagnostic = d(1, 1, 1, 8, 
				XMLSyntaxErrorCode.NoGrammarConstraints,
				"No grammar constraints (DTD or XML Schema).");
		CodeAction expectedCodeAction = ca(expectedDiagnostic, teOp(POM_FILE, 1, 8, 1, 8, 
				String.format(XSI_VALUE_PATTERN, getDocumentLineSeparator(xmlDocument))));
		testCodeAction(xmlDocument,false, expectedDiagnostic, expectedCodeAction);
	}
	
	// The following org.eclipse.m2e.core.internal.IMavenConstants hint quickfixes are to be moved to LemMinX-Maven:
	//
	// EDITOR_HINT_PARENT_VERSION (from m2e.ui)
	@Test
	public void testCodeActionsForParentVersionRemoval() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/codeactions-test/pom-duplication-of-version.xml", xmlLanguageService);

		// Test diagnostic and code action for duplicated parent version
		Diagnostic expectedDiagnostic = d(11, 11, 11, 16, 
				MavenSyntaxErrorCode.DuplicationOfParentVersion,
				"Version is duplicate of parent version");
		CodeAction expectedCodeAction = ca(expectedDiagnostic, teOp(POM_FILE, 11, 2, 11, 26, ""));
		testCodeAction(xmlDocument,true, expectedDiagnostic, expectedCodeAction);
	}

	// EDITOR_HINT_PARENT_GROUP_ID (from m2e.ui)
	@Test
	public void testCodeActionsForParentGroupIdRemoval() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/codeactions-test/pom-duplication-of-groupid.xml", xmlLanguageService);

		// Test diagnostic and code action for duplicated parent group ID
		Diagnostic expectedDiagnostic = d(11, 11, 11, 27, 
				MavenSyntaxErrorCode.DuplicationOfParentGroupId,
				"GroupId is duplicate of parent groupId");
		CodeAction expectedCodeAction = ca(expectedDiagnostic, teOp(POM_FILE, 11, 2, 11, 37, ""));
		testCodeAction(xmlDocument,true, expectedDiagnostic, expectedCodeAction);
	}
	
	// EDITOR_HINT_MANAGED_DEPENDENCY_OVERRIDE (from m2e.ui)
	@Test
	public void testCodeActionsForManagedDependencyOverride() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/codeactions-test/pom-overriding-of-managed-version.xml", xmlLanguageService);

		String uri = xmlDocument.getDocumentURI();
		File file = new File(new URI(uri).getPath());
		File parentFile = new File (file.getParentFile(), "parent/pom.xml");
	
		Diagnostic expectedDiagnostic = d(17, 15, 17, 21, 
				MavenSyntaxErrorCode.OverridingOfManagedDependency,
				"Overriding managed version 3.18.0 for commons-lang3");
		
		// Fake location for managed version 
		Map<String, String> data = new HashMap<>();
		data.put("managedVersionLocation", parentFile.toURI().toString());
		data.put("managedVersionLine",  "19");
		data.put("managedVersionColumn",  "18");
		data.put("groupId",  "org.apache.commons");
		data.put("artifactId",  "commons-lang3");
		expectedDiagnostic.setData(data);
		
		// Test diagnostic and code action for a different version value
		CodeAction expectedCodeAction_1 = ca(expectedDiagnostic, teOp(POM_FILE, 17, 6, 17, 31, ""));
		CodeAction expectedCodeAction_2 = ca(expectedDiagnostic, teOp(POM_FILE, 17, 31, 17, 31, "<!--$NO-MVN-MAN-VER$-->"));
		CodeAction expectedCodeAction_3 = ca(expectedDiagnostic, 
				new Command("Open declaration of managed version", "xml.open.uri", 
						Arrays.asList(parentFile.toURI().toString() + 
								"#L"  + data.get("managedVersionLine") + "," + data.get("managedVersionColumn") )));;
		testCodeAction(xmlDocument,false, expectedDiagnostic, expectedCodeAction_1, expectedCodeAction_2, expectedCodeAction_3);
	}

	@Test
	public void testCodeActionsForManagedDependencyDuplicate() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/codeactions-test/pom-duplication-of-managed-version.xml", xmlLanguageService);

		String uri = xmlDocument.getDocumentURI();
		File file = new File(new URI(uri).getPath());
		File parentFile = new File (file.getParentFile(), "parent/pom.xml");

		Diagnostic expectedDiagnostic = d(17, 15, 17, 21, 
				MavenSyntaxErrorCode.OverridingOfManagedDependency,
				"Duplicating managed version 3.18.0 for commons-lang3");
		
		// Fake location for managed version 
		Map<String, String> data = new HashMap<>();
		data.put("managedVersionLocation", parentFile.toURI().toString());
		data.put("managedVersionLine",  "19");
		data.put("managedVersionColumn",  "18");
		data.put("groupId",  "org.apache.commons");
		data.put("artifactId",  "commons-lang3");
		expectedDiagnostic.setData(data);

		// Test diagnostic and code action for the same version value
		CodeAction expectedCodeAction_1 = ca(expectedDiagnostic, teOp(POM_FILE, 17, 6, 17, 31, ""));
		CodeAction expectedCodeAction_2 = ca(expectedDiagnostic, teOp(POM_FILE, 17, 31, 17, 31, "<!--$NO-MVN-MAN-VER$-->"));
		CodeAction expectedCodeAction_3 = ca(expectedDiagnostic, 
				new Command("Open declaration of managed version", "xml.open.uri", 
						Arrays.asList(parentFile.toURI().toString() + 
								"#L"  + data.get("managedVersionLine") + "," + data.get("managedVersionColumn") )));;
		
		testCodeAction(xmlDocument,true, expectedDiagnostic, expectedCodeAction_1, expectedCodeAction_2, expectedCodeAction_3);
	}
	
	// EDITOR_HINT_MANAGED_PLUGIN_OVERRIDE (from m2e.ui)
	@Test
	public void testCodeActionsForManagedPluginOverride() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/codeactions-test/pom-overriding-of-managed-plugin.xml", xmlLanguageService);

		String uri = xmlDocument.getDocumentURI();
		File file = new File(new URI(uri).getPath());
		File parentFile = new File (file.getParentFile(), "parent/pom.xml");
	
		Diagnostic expectedDiagnostic = d(24, 21, 24, 26, 
				MavenSyntaxErrorCode.OverridingOfManagedPlugin,
				"Overriding managed version 3.8.1 for maven-compiler-plugin");
		
		// Fake location for managed version 
		Map<String, String> data = new HashMap<>();
		data.put("managedVersionLocation", parentFile.toURI().toString());
		data.put("managedVersionLine",  "30");
		data.put("managedVersionColumn",  "20");
		data.put("groupId",  "org.apache.maven.plugins");
		data.put("artifactId",  "maven-compiler-plugin");
		data.put("profile",  "OverrideProfile");
		expectedDiagnostic.setData(data);
		
		// Test diagnostic and code action for a different version value
		CodeAction expectedCodeAction_1 = ca(expectedDiagnostic, teOp(POM_FILE, 24, 12, 24, 36, ""));
		CodeAction expectedCodeAction_2 = ca(expectedDiagnostic, teOp(POM_FILE, 24, 36, 24, 36, "<!--$NO-MVN-MAN-VER$-->"));
		CodeAction expectedCodeAction_3 = ca(expectedDiagnostic, 
				new Command("Open declaration of managed version", "xml.open.uri", 
						Arrays.asList(parentFile.toURI().toString() + 
								"#L"  + data.get("managedVersionLine") + "," + data.get("managedVersionColumn") )));;
		testCodeAction(xmlDocument,false, expectedDiagnostic, expectedCodeAction_1, expectedCodeAction_2, expectedCodeAction_3);
	}

	@Test
	public void testCodeActionsForManagedPluginDuplicate() throws Exception {
		DOMDocument xmlDocument = createDOMDocument("/codeactions-test/pom-duplication-of-managed-plugin.xml", xmlLanguageService);

		String uri = xmlDocument.getDocumentURI();
		File file = new File(new URI(uri).getPath());
		File parentFile = new File (file.getParentFile(), "parent/pom.xml");

		Diagnostic expectedDiagnostic = d(18, 17, 18, 22, 
				MavenSyntaxErrorCode.OverridingOfManagedPlugin,
				"Duplicating managed version 3.8.1 for maven-compiler-plugin");
		
		// Fake location for managed version 
		Map<String, String> data = new HashMap<>();
		data.put("managedVersionLocation", parentFile.toURI().toString());
		data.put("managedVersionLine",  "30");
		data.put("managedVersionColumn",  "20");
		data.put("groupId",  "org.apache.maven.plugins");
		data.put("artifactId",  "maven-compiler-plugin");
		expectedDiagnostic.setData(data);

		// Test diagnostic and code action for the same version value
		CodeAction expectedCodeAction_1 = ca(expectedDiagnostic, teOp(POM_FILE, 18, 8, 18, 32, ""));
		CodeAction expectedCodeAction_2 = ca(expectedDiagnostic, teOp(POM_FILE, 18, 32, 18, 32, "<!--$NO-MVN-MAN-VER$-->"));
		CodeAction expectedCodeAction_3 = ca(expectedDiagnostic, 
				new Command("Open declaration of managed version", "xml.open.uri", 
						Arrays.asList(parentFile.toURI().toString() + 
								"#L"  + data.get("managedVersionLine") + "," + data.get("managedVersionColumn") )));;
		
		testCodeAction(xmlDocument,true, expectedDiagnostic, expectedCodeAction_1, expectedCodeAction_2, expectedCodeAction_3);
	}
	
	
	
//	@TODO: EDITOR_HINT_CONFLICTING_LIFECYCLEMAPPING (from m2e.core)
//	@TODO: EDITOR_HINT_NOT_COVERED_MOJO_EXECUTION (from m2e.core)
//	@TODO: EDITOR_HINT_MISSING_CONFIGURATOR (from m2e.core)
//	@TODO: EDITOR_HINT_IMPLICIT_LIFECYCLEMAPPINGEDITOR_HINT_IMPLICIT_LIFECYCLEMAPPING
	
	private void testCodeAction(DOMDocument xmlDocument, boolean ignoreNoGrammar, Diagnostic expectedDiagnostic, CodeAction... expectedCodeAction) throws BadLocationException {
		// Test for expected diagnostics is returned
		ContentModelSettings settings = createContentModelSettings(ignoreNoGrammar);
		xmlLanguageService.setDocumentProvider((uri) -> xmlDocument);
		xmlLanguageService.doSave(new SettingsSaveContext(settings));

		List<Diagnostic> actual = xmlLanguageService.doDiagnostics(xmlDocument, settings.getValidation(),
				Collections.emptyMap(), () -> {
				}).stream().filter(a -> a.getCode()!= null && a.getCode().getLeft() != null && a.getCode().getLeft().equals(expectedDiagnostic.getCode().getLeft()))
				.collect(Collectors.toList());
//		if (expected == null) {
//			assertTrue(actual.isEmpty());
//			return;
//		}
		assertDiagnostics(actual, Arrays.asList(expectedDiagnostic), true);		

		// Test for expected code action is returned
		testCodeActionsFor(xmlDocument.getText(), expectedDiagnostic, null, (String) null, POM_FILE, 
				sharedSettings, xmlLanguageService, -1, expectedCodeAction);
	}
	
	// TODO: Move this change back to XMLAssert
	public static void assertDiagnostics(List<Diagnostic> actual, List<Diagnostic> expected, boolean filter) {
		List<Diagnostic> received = actual;
		final boolean filterMessage;
		if (expected != null && !expected.isEmpty() && !StringUtils.isEmpty(expected.get(0).getMessage())) {
			filterMessage = true;
		} else {
			filterMessage = false;
		}
		if (filter) {
			received = actual.stream().map(d -> {
				Diagnostic simpler = new Diagnostic(d.getRange(), "");
				if (d.getCode() != null && !StringUtils.isEmpty(d.getCode().getLeft())) {
					simpler.setCode(d.getCode());
				}
				if (filterMessage) {
					simpler.setMessage(d.getMessage());
				}
				if (d.getData() != null) {
					simpler.setData(d.getData());
				}
				return simpler;
			}).collect(Collectors.toList());
		}
		
		// Don't compare message of diagnosticRelatedInformation
		for (Diagnostic diagnostic : received) {
			List<DiagnosticRelatedInformation> diagnosticRelatedInformations = diagnostic.getRelatedInformation();
			if (diagnosticRelatedInformations != null) {
				for (DiagnosticRelatedInformation diagnosticRelatedInformation : diagnosticRelatedInformations) {
					diagnosticRelatedInformation.setMessage("");
				}
			}
		}
		assertIterableEquals(expected, received, "Unexpected diagnostics:\n" + actual);
	}
	
	public static List<CodeAction> testCodeActionsFor(String xml, Diagnostic diagnostic, Range range, String catalogPath,
			String fileURI, SharedSettings sharedSettings, XMLLanguageService xmlLanguageService, int index,
			CodeAction... expected) throws BadLocationException {
		int offset = xml.indexOf('|');
		if (offset != -1) {
			xml = xml.substring(0, offset) + xml.substring(offset + 1);
		}
		TextDocument document = new TextDocument(xml.toString(), fileURI != null ? fileURI : POM_FILE);

		// Use range from the text (if marked by "|"-char or from diagnostics
		if (offset != -1) {
			Position position = document.positionAt(offset);
			range = new Range(position, position);
		} else if (range == null && diagnostic != null) {
			range = diagnostic.getRange();
		}
		
		// Otherwise, range is to be specified in parameters
		assertNotNull(range, "Range cannot be null");

		if (xmlLanguageService == null) {
			xmlLanguageService = new MavenLanguageService();
		}

		ContentModelSettings cmSettings = new ContentModelSettings();
		cmSettings.setUseCache(false);
		if (catalogPath != null) {
			// Configure XML catalog for XML schema
			cmSettings.setCatalogs(new String[] { catalogPath });
		}
		xmlLanguageService.doSave(new SettingsSaveContext(cmSettings));

		CodeActionContext context = new CodeActionContext();
		context.setDiagnostics(Arrays.asList(diagnostic));
		DOMDocument xmlDoc = DOMParser.getInstance().parse(document, xmlLanguageService.getResolverExtensionManager());
		xmlLanguageService.setDocumentProvider((uri) -> xmlDoc);

		List<CodeAction> actual = xmlLanguageService.doCodeActions(context, range, xmlDoc, sharedSettings, () -> {})
					.stream().filter(ca -> ca.getDiagnostics().contains(diagnostic)).toList();

		// Clone
		// Creating a gson object
		Gson gson = new Gson();
		// Converting the list into a json string
		String jsonstring = gson.toJson(actual);

		// Converting the json string
		// back into a list
		CodeAction[] cloned_list = gson.fromJson(jsonstring, CodeAction[].class);

		// Only test the code action at index if a proper index is given
		if (index >= 0) {
			assertCodeActions(Arrays.asList(actual.get(index)), Arrays.asList(expected).get(index));
			return Arrays.asList(cloned_list);
		}
		assertCodeActions(actual, expected);
		return Arrays.asList(cloned_list);
	}

	
	private static ContentModelSettings  createContentModelSettings(boolean ignoreNoGrammar) {
		ContentModelSettings settings = new ContentModelSettings();
		settings.setUseCache(false);
		XMLValidationRootSettings problems = new XMLValidationRootSettings();
		if (ignoreNoGrammar) {
			problems.setNoGrammar("ignore");
		}
		settings.setValidation(problems);
		return settings;
	}
}
