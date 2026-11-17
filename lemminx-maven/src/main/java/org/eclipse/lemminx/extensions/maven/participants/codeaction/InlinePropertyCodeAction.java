/*******************************************************************************
 * Copyright (c) 2023, 2026 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.codeaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.maven.project.MavenProject;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.CodeActionFactory;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.ParticipantUtils;
import org.eclipse.lemminx.services.extensions.codeaction.ICodeActionParticipant;
import org.eclipse.lemminx.services.extensions.codeaction.ICodeActionRequest;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public class InlinePropertyCodeAction implements ICodeActionParticipant {
	private static final Logger LOGGER = Logger.getLogger(InlinePropertyCodeAction.class.getName());
	private final MavenLemminxExtension plugin;
	
	public InlinePropertyCodeAction(MavenLemminxExtension plugin) {
		this.plugin = plugin;
	}

	@Override
	public void doCodeActionUnconditional(ICodeActionRequest request, List<CodeAction> codeActions, CancelChecker cancelChecker)  throws CancellationException {
		Range range = request.getRange();
		if (range == null) {
			return;
		}

		cancelChecker.checkCanceled();
		try {
			DOMDocument document = request.getDocument();
			int startOffset = document.offsetAt(range.getStart());
			Map.Entry<Range, String> mavenProperty = ParticipantUtils.getMavenProperty(document.findNodeAt(startOffset), startOffset);
			if (mavenProperty == null) {
				return;
			}
			
			cancelChecker.checkCanceled();
			MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(document);
			if (project != null) {
				cancelChecker.checkCanceled();
				Map<String, String> properties = ParticipantUtils.getMavenProjectProperties(project);
				String value = properties.get(mavenProperty.getValue());
				if (value != null) {
					cancelChecker.checkCanceled();
					
					List<TextEdit> textEdits = new ArrayList<>();
					collectInlinePropertyTextEdits(document.getDocumentElement(), 
							"${" + mavenProperty.getValue() + "}", 
							value, textEdits, cancelChecker);

					if (textEdits.size() > 0) {
						// Replace the property with its value only in current node
						TextEdit thisEdit = textEdits.stream()
								.filter(e -> rangeContains(e.getRange(), range.getStart()))
								.findFirst().orElse(null);
						if (thisEdit != null) {
							CodeAction ca = CodeActionFactory.replace( 
									"Inline Property", thisEdit.getRange(), thisEdit.getNewText(), 
									document.getTextDocument(),  null);
							ca.setDiagnostics(Collections.emptyList());
							codeActions.add(ca);
						}
					} 
					
					if (textEdits.size() > 1) {
						// Replace the property with its value in entire document
						CodeAction ca = CodeActionFactory.replace( 
								"Inline all Properties", 
								textEdits, document.getTextDocument(),  null);
						ca.setDiagnostics(Collections.emptyList());
						codeActions.add(ca);
					}
				}
			}
		} catch (CancellationException e) {
			throw e;
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
	}
	
	private static boolean rangeContains(Range range, Position position) {
		Position start = range.getStart();
		if (start.getLine() > position.getLine()) {
			return false;
		}
		if (start.getLine() == position.getLine() && start.getCharacter() > position.getCharacter()) {
			return false;
		}
		Position end = range.getEnd();
		if (end.getLine() < position.getLine()) {
			return false;
		}
		if (end.getLine() == position.getLine() && end.getCharacter() < position.getCharacter()) {
			return false;
		}
		return true;
	}
	
	void collectInlinePropertyTextEdits(DOMElement rootElement, String property, String value,
			List<TextEdit> textEditss, CancelChecker cancelChecker) throws CancellationException {
		cancelChecker.checkCanceled();

		// Check this element's text
		collectInElementTextEdits(rootElement, property, value, textEditss, cancelChecker);
		
		// collect in this element's children 
		rootElement.getChildren().stream().filter(DOMElement.class::isInstance).map(DOMElement.class::cast)
			.forEach(child -> collectInlinePropertyTextEdits(child, property, value, textEditss, cancelChecker));
	}

	void collectInElementTextEdits(DOMElement element, String property, String value, 
			List<TextEdit> textEditss, CancelChecker cancelChecker) throws CancellationException {
		TextDocument textDocument = element.getOwnerDocument().getTextDocument();
		DOMUtils.findElementTextChildren(element).stream()
			.filter(text -> text.getData().contains(property))
			.forEach(text -> {
				String data = text.getData();
				int index = 0;
				for (index = data.indexOf(property); index != -1; index = data.indexOf(property, index + property.length())) {
					cancelChecker.checkCanceled();
					try {
						int propertyUseStart = text.getStart() + index;
						Range range = new Range(textDocument.positionAt(propertyUseStart), 
								textDocument.positionAt(propertyUseStart + property.length()));
						textEditss.add(new TextEdit(range, value));
					} catch (BadLocationException e) {
						LOGGER.log(Level.SEVERE, e.getMessage(), e);
						return;
					}
				}
			});
	}
}
