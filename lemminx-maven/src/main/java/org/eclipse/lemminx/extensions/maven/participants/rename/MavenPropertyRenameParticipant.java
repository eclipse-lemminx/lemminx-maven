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
import static org.eclipse.lemminx.extensions.maven.utils.ParticipantUtils.getMavenProperty;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.maven.project.MavenProject;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMText;
import org.eclipse.lemminx.extensions.maven.MavenInitializationException;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.MavenModelOutOfDatedException;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.ParticipantUtils;
import org.eclipse.lemminx.services.extensions.rename.IPrepareRenameRequest;
import org.eclipse.lemminx.services.extensions.rename.IRenameParticipant;
import org.eclipse.lemminx.services.extensions.rename.IRenameRequest;
import org.eclipse.lemminx.services.extensions.rename.IRenameResponse;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SnippetTextEdit;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class MavenPropertyRenameParticipant implements IRenameParticipant {
	private static final Logger LOGGER = Logger.getLogger(MavenPropertyRenameParticipant.class.getName());

	private static final String PROPERTY_START = "${";
	private static final String PROPERTY_END = "}";
	
	private final MavenLemminxExtension plugin;

	public MavenPropertyRenameParticipant(MavenLemminxExtension plugin) {
		this.plugin = plugin;
	}

	@Override
	public Either<Range, PrepareRenameResult> prepareRename(IPrepareRenameRequest request,
			CancelChecker cancelChecker) {
		try {
			DOMDocument document = request.getXMLDocument();
			DOMNode node = request.getNode();
			int offset = request.getOffset();
	
			String propertyName = null;
			Range propertyRange = null;
			if (node instanceof DOMText textNode) {
				Map.Entry<Range, String> mavenProperty = getMavenProperty(textNode, offset);
				if (mavenProperty != null) {
					cancelChecker.checkCanceled();
					propertyName = mavenProperty.getValue();
					propertyRange = mavenProperty.getKey();
				}
			} else if (node instanceof DOMElement element) {
				Range range = getMavenPropertyDefinitionRange(offset, element);
				if (range != null) {
					cancelChecker.checkCanceled();
					propertyName = element.getNodeName();
					propertyRange =range;
				}
			}
	
			if (propertyName == null || propertyRange == null) {
				return null;
			}
	
			// Check Maven property
			cancelChecker.checkCanceled();
			MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(document);
	
			cancelChecker.checkCanceled();
			Map<String, String> properties = ParticipantUtils.getMavenProjectProperties(project);
	
			cancelChecker.checkCanceled();
			return properties.get(propertyName) == null ? null : Either.forLeft(propertyRange);
		} catch (MavenInitializationException | MavenModelOutOfDatedException e) {
			// - Maven is initializing
			// - or parse of maven model with DOM document is out of dated
			// -> catch the error to avoid breaking XML prepare rename from LemMinX
			return null;
		}
	}

	@Override
	public void doRename(IRenameRequest request, IRenameResponse renameResponse, CancelChecker cancelChecker) {
		try {
			DOMDocument document = request.getXMLDocument();
			DOMNode node = request.getNode();
			int offset = request.getOffset();
			String newPropertyName = request.getNewText();
			
			String propertyName = null;
			if (node instanceof DOMText textNode) {
				Map.Entry<Range, String> mavenProperty = getMavenProperty(textNode, offset);
				if (mavenProperty != null) {
					cancelChecker.checkCanceled();
					propertyName = mavenProperty.getValue();
				}
			} else if (node instanceof DOMElement element) {
				Range range = getMavenPropertyDefinitionRange(offset, element);
				if (range != null) {
					cancelChecker.checkCanceled();
					propertyName = element.getNodeName();
				}
			}
	
			if (propertyName == null) {
				return;
			}
	
			// Check Maven property
			cancelChecker.checkCanceled();
			MavenProject thisProject = plugin.getProjectCache().getLastSuccessfulMavenProject(document);
			if (thisProject == null) {
				return;
			}
	
			cancelChecker.checkCanceled();
			Map<String, String> properties = ParticipantUtils.getMavenProjectProperties(thisProject);
			if (properties.get(propertyName) == null) {
				return;
			}
	
			cancelChecker.checkCanceled();
			LinkedHashSet<MavenProject> projects = new LinkedHashSet<>();
			projects.add(thisProject);
			plugin.getCurrentWorkspaceProjects(true).stream().forEach(child -> 
				projects.addAll(findParentsOfChildProject(thisProject, child)));
	
			URI thisProjectUri = ParticipantUtils.normalizedUri(document.getDocumentURI());
			final String oldPropertyName = propertyName;
			projects.stream().forEach(project -> {
				cancelChecker.checkCanceled();
				URI projectUri = ParticipantUtils.normalizedUri(project.getFile().toURI().toString());
				DOMDocument projectDocumentt = null;
				if (projectUri.equals(thisProjectUri)) {
					projectDocumentt = document;
				} else {
					projectDocumentt = org.eclipse.lemminx.utils.DOMUtils.loadDocument(
						project.getFile().toURI().toString(),
						request.getNode().getOwnerDocument().getResolverExtensionManager());
				}
				
				cancelChecker.checkCanceled();
				// Collect Text Edits for the document
				List<TextEdit> projectTextEdits = new ArrayList<>();
				collectPropertyElementTextEdits(projectDocumentt, oldPropertyName, newPropertyName, projectTextEdits, cancelChecker);
				collectPropertyUseTextEdits(projectDocumentt.getDocumentElement(), oldPropertyName, newPropertyName, projectTextEdits, cancelChecker);
				VersionedTextDocumentIdentifier projectVersionedTextDocumentIdentifier = new VersionedTextDocumentIdentifier(
						projectDocumentt.getTextDocument().getUri(), projectDocumentt.getTextDocument().getVersion());
				List<Either<TextEdit, SnippetTextEdit>> edits = projectTextEdits.stream()
						.map(Either::<TextEdit, SnippetTextEdit>forLeft)
						.toList();
				renameResponse.addTextDocumentEdit(new TextDocumentEdit(projectVersionedTextDocumentIdentifier, edits));
			});
		} catch (MavenInitializationException | MavenModelOutOfDatedException e) {
			// - Maven is initializing
			// - or parse of maven model with DOM document is out of dated
			// -> catch the error to avoid breaking XML rename from LemMinX
		}
	}

	/*
	 *  Returns the list of parent projects between the given child and parent project, 
	 *  the list includes the given child, but not the parent.
	 */
	private static List<MavenProject> findParentsOfChildProject(MavenProject parent, MavenProject child) {
		List<MavenProject> parents = new LinkedList<>();
		MavenProject currentChild = child;
		URI childProjectUri = ParticipantUtils.normalizedUri(currentChild.getFile().toURI().toString());
		URI thisProjectUri = ParticipantUtils.normalizedUri(parent.getFile().toURI().toString());
		
		while (currentChild != null) {
			parents.add(currentChild); // Include the child as well
			if (childProjectUri.equals(thisProjectUri)) {
				break; // Stop searching
			}
			currentChild = currentChild.getParent();
			if (currentChild != null) {
				childProjectUri = ParticipantUtils.normalizedUri(currentChild.getFile().toURI().toString());
			}
		}
		return currentChild != null ? parents : Collections.emptyList();
	}
	
	private static void collectPropertyElementTextEdits(DOMDocument document, String propertyName, String newPropertyName,
			List<TextEdit> textEdits, CancelChecker cancelChecker) {
		DOMUtils.findChildElement(document.getDocumentElement(), PROPERTIES_ELT).ifPresent(properties -> {
			DOMUtils.findChildElement(properties, propertyName).ifPresent(property -> {
				int startTagOpenOffset = property.getStartTagOpenOffset() + 1;
				int endTagOpenOffset = property.getEndTagOpenOffset() + 2;
				try {
					Range startRange = new Range(document.positionAt(startTagOpenOffset), 
							document.positionAt(startTagOpenOffset + propertyName.length()));
					Range endRange = new Range(document.positionAt(endTagOpenOffset), 
							document.positionAt(endTagOpenOffset + propertyName.length()));
					textEdits.add(new TextEdit(startRange, newPropertyName));
					textEdits.add(new TextEdit(endRange, newPropertyName));
				} catch (BadLocationException e) {
					LOGGER.log(Level.SEVERE, e.getMessage(), e);
				}
			}); 
		});
	}
	
	private static void collectPropertyUseTextEdits(DOMElement rootElement, String property, String newPropertyName,
			List<TextEdit> textEditss, CancelChecker cancelChecker) throws CancellationException {
		cancelChecker.checkCanceled();
	
		// Check this element's text
		collectInElementTextEdits(rootElement, property, newPropertyName, textEditss, cancelChecker);
		
		// collect in this element's children 
		rootElement.getChildren().stream().filter(DOMElement.class::isInstance).map(DOMElement.class::cast)
			.forEach(child -> collectPropertyUseTextEdits(child, property, newPropertyName, textEditss, cancelChecker));
	}

	private static void collectInElementTextEdits(DOMElement element, String propertyName, String newPropertyName, 
			List<TextEdit> textEditss, CancelChecker cancelChecker) throws CancellationException {
		TextDocument textDocument = element.getOwnerDocument().getTextDocument();
		String propertyUse = PROPERTY_START + propertyName + PROPERTY_END;
		DOMUtils.findElementTextChildren(element).stream()
			.filter(text -> text.getData().contains(propertyUse))
			.forEach(text -> {
				String data = text.getData();
				int index = 0;
				for (index = data.indexOf(propertyUse); index != -1; index = data.indexOf(propertyUse, index + propertyUse.length())) {
					cancelChecker.checkCanceled();
					try {
						int propertyUseStart = text.getStart() + index;
						int replaceStart = propertyUseStart + PROPERTY_START.length();
						int replaceEnd= replaceStart + propertyName.length();
						Range range = new Range(textDocument.positionAt(replaceStart), 
								textDocument.positionAt(replaceEnd));
						textEditss.add(new TextEdit(range,  newPropertyName));
					} catch (BadLocationException e) {
						LOGGER.log(Level.SEVERE, e.getMessage(), e);
						return;
					}
				}
			});
	}

	private static Range getMavenPropertyDefinitionRange(int offset, DOMElement propertyElement) {
		if (propertyElement == null) {
			return null;
		}
		DOMElement parentElement = propertyElement.getParentElement();
		if (parentElement == null || !PROPERTIES_ELT.equals(parentElement.getNodeName())) {
			return null;
		}

		if (!propertyElement.hasEndTag()) {
			return null;
		}

		int startTagOpenOffset = propertyElement.getStartTagOpenOffset() + 1;
		int startTagCloseOffset = propertyElement.getStartTagCloseOffset();
		int endTagOpenOffset = propertyElement.getEndTagOpenOffset() + 2;
		int endTagCloseOffset = propertyElement.getEndTagCloseOffset();
		
		DOMDocument document = propertyElement.getOwnerDocument();
		Range range = null;
		try {
			if (offset >= startTagOpenOffset && offset < startTagCloseOffset) {
				range = new Range(document.positionAt(startTagOpenOffset), document.positionAt(startTagCloseOffset));
			} else if (offset >= endTagOpenOffset && offset < endTagCloseOffset) {
				range = new Range(document.positionAt(endTagOpenOffset), document.positionAt(endTagCloseOffset));
			}
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}

		return range;
	}
}
