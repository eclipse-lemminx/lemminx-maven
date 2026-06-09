package org.eclipse.lemminx.extensions.maven.utils;

import static org.eclipse.lemminx.XMLAssert.testCodeActionsFor;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lemminx.XMLAssert.SettingsSaveContext;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.DiagnosticUtils;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.contentmodel.settings.ContentModelSettings;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lemminx.settings.SharedSettings;
import org.eclipse.lemminx.utils.StringUtils;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticRelatedInformation;

public class MavenLemminxXMLAssert {
	
	public static void testCodeAction( SharedSettings sharedSettings, XMLLanguageService xmlLanguageService, DOMDocument xmlDocument, boolean ignoreNoGrammar, Diagnostic expectedDiagnostic, CodeAction... expectedCodeAction) throws BadLocationException {
		// Test for expected diagnostics is returned
		ContentModelSettings settings = MavenLemminxTestsUtils.createContentModelSettings(ignoreNoGrammar);
		xmlLanguageService.setDocumentProvider((uri) -> xmlDocument);
		xmlLanguageService.doSave(new SettingsSaveContext(settings));

		List<Diagnostic> actual = xmlLanguageService.doDiagnostics(xmlDocument, settings.getValidation(),
				Collections.emptyMap(), () -> {
				}).stream().filter(a -> a.getCode()!= null & a.getCode().getLeft().equals(expectedDiagnostic.getCode().getLeft()))
				.collect(Collectors.toList());
//		if (expected == null) {
//			assertTrue(actual.isEmpty());
//			return;
//		}
		assertDiagnostics(actual, Arrays.asList(expectedDiagnostic), true);		

		// Test for expected code action is returned
		testCodeActionsFor(xmlDocument.getText(), expectedDiagnostic, null, (String) null, "pom.xml", 
				sharedSettings, xmlLanguageService, -1, expectedCodeAction);
	}
	
	// TODO: Move this change back to XMLAssert
	public static void assertDiagnostics(List<Diagnostic> actual, List<Diagnostic> expected, boolean filter) {
		List<Diagnostic> received = actual;
		final boolean filterMessage;
		if (expected != null && !expected.isEmpty() && !StringUtils.isEmpty(DiagnosticUtils.getDiagnosticMessage(expected.get(0)))) {
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
}
