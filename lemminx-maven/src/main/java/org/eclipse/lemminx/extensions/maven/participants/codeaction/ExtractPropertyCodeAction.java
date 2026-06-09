/*******************************************************************************
 * Copyright (c) 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.codeaction;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PARENT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PROJECT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PROPERTIES_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.VERSION_ELT;
import static org.eclipse.lemminx.extensions.maven.MavenLemminxExtension.key;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.commons.CodeActionFactory;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMText;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.ParticipantUtils;
import org.eclipse.lemminx.services.extensions.codeaction.ICodeActionParticipant;
import org.eclipse.lemminx.services.extensions.codeaction.ICodeActionRequest;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.SnippetTextEdit;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class ExtractPropertyCodeAction implements ICodeActionParticipant {
	private static final Logger LOGGER = Logger.getLogger(ExtractPropertyCodeAction.class.getName());
	private final MavenLemminxExtension plugin;
	
	public ExtractPropertyCodeAction(MavenLemminxExtension plugin) {
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
			int endOffset = document.offsetAt(range.getStart());
			
			// We cannot search by start nor by end of text node as these positions
			// will be treaded as parts of 'DOMElement'. 
			// Also, value length cannot be less than 1
			DOMNode node = document.findNodeAt(startOffset);
			if (node == null) {
				return;
			}

			if(node.isElement()) {
				 Optional<DOMText> text = node.getChildren().stream()
					.filter(DOMText.class::isInstance).map(DOMText.class::cast).findFirst();
				 if (text.isPresent()) {
					 node = text.get();
				 }
			}
			if (!node.isText() || !DOMNode.isIncluded(node, startOffset) 
					|| !DOMNode.isIncluded(node, endOffset))  {
				return;
			}
			
			// We cannot extract into a variable the values of project's ``<groupId>`, `<artifactId>`, 
			// `<packaging>` etc.
			// The only value extraction available for project's `<version>` element.
			if(!checkParentElement(node)) {
				return;
			}
			
			String propertyValue = node.getNodeValue();
			if (propertyValue == null || propertyValue.trim().isBlank()) {
				return;
			}
			
			Map.Entry<Range, String> mavenProperty = ParticipantUtils.getMavenProperty(document.findNodeAt(startOffset), startOffset);
			if (mavenProperty != null) {
				// We cannot extract property from an existing property
				return;
			}
			
			cancelChecker.checkCanceled();
			MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(document);
			if (project != null) {
				cancelChecker.checkCanceled();
				Map<String, String> properties = ParticipantUtils.getMavenProjectProperties(project);
				String propertyName = constructPropertyName(project, node, properties, cancelChecker);
				
				// Gather `<properties/> header text edits
				cancelChecker.checkCanceled();
				LinkedHashSet<MavenProject> parentProjects = findParentProjects(project);
				LinkedHashMap<MavenProject, TextDocumentEdit> headerTextEdits = new LinkedHashMap<>();
				String newline = request.getXMLGenerator().getLineDelimiter();
				String indent = DOMUtils.getOneLevelIndent(request);
				URI thisProjectUri = ParticipantUtils.normalizedUri(document.getDocumentURI());
				parentProjects.stream().forEach(p -> {
					cancelChecker.checkCanceled();
					URI projectUri = ParticipantUtils.normalizedUri(p.getFile().toURI().toString());
					DOMDocument projectDocumentt = null;
					if (projectUri.equals(thisProjectUri)) {
						projectDocumentt = document;
					} else {
						projectDocumentt = org.eclipse.lemminx.utils.DOMUtils.loadDocument(
							p.getFile().toURI().toString(),
							document.getResolverExtensionManager());
					}

					TextEdit projectHeaderTextEdit = createPropertiesHeaderTextEdit(projectDocumentt, newline, indent,
							propertyName, propertyValue, cancelChecker);
					if (projectHeaderTextEdit != null) {
						headerTextEdits.put(p, createProjectTextDocumentEdit(
								projectDocumentt.getTextDocument(), Arrays.asList(projectHeaderTextEdit)));
					}
				});
				
				cancelChecker.checkCanceled();
				List<String> exactValueProperties = properties.entrySet().stream().filter(e -> propertyValue.equals(e.getValue()))
						.map(Entry::getKey).toList();
				
				// Create code action to extract a single value as new property in current module
				TextEdit headerTextEdit = createPropertiesHeaderTextEdit(document, newline, indent,
						propertyName, propertyValue, cancelChecker);
				Range singleRange = getSingleExtractPropertyRange(request, (DOMText)node, propertyName);
				cancelChecker.checkCanceled();

				// The list should contain header and one extracted property
				if (singleRange != null) {
					//	Extract as new property in current module
					List<TextEdit> singleTextEdits = new ArrayList<>();
					singleTextEdits.add(headerTextEdit);
					singleTextEdits.add(new TextEdit(singleRange, "${" + propertyName + "}"));
					CodeAction extractCodeAction = CodeActionFactory.replace( 
							"Extract as new property in current module", 
							singleTextEdits, document.getTextDocument(),  null);
					extractCodeAction.setDiagnostics(Collections.emptyList());
					codeActions.add(extractCodeAction);

					//	Replace with existing "${already.existing.property}" property
					exactValueProperties.stream().forEach(p -> {
						List<TextEdit> singleReplaceTextEdits = new ArrayList<>();
						singleReplaceTextEdits.add(new TextEdit(singleRange, "${" + p + "}"));
						CodeAction replaceCodeAction = CodeActionFactory.replace( 
								"Replace with existing \"$" + p + "}\" property", 
								singleReplaceTextEdits, document.getTextDocument(),  null);
						replaceCodeAction.setDiagnostics(Collections.emptyList());
						codeActions.add(replaceCodeAction);
					});
					
					//	Extract as new property in parent ggg:aaa:vvv
					parentProjects.stream().forEach(p -> {
						TextDocumentEdit projectHeaderEdit = headerTextEdits.get(p);
						if (projectHeaderEdit != null) {
							TextDocumentEdit singleBodyEdit = createProjectTextDocumentEdit(document.getTextDocument(), 
									Arrays.asList(new TextEdit(singleRange, "${" + propertyName + "}")));
							
							CodeAction extractAsNewCodeAction = createReplaceCodeActione("Extract as new property in  parent \"" + key(p) + "\"",
									Arrays.asList(projectHeaderEdit, singleBodyEdit), null);
							extractAsNewCodeAction.setDiagnostics(Collections.emptyList());
							codeActions.add(extractAsNewCodeAction);
						}
					});
				}

				// Create code action to extract all the value entries into a properties
				List<Range> multipleRanges = new ArrayList<>();
				collectExtractPropertyTextEdits(request, document.getDocumentElement(), (DOMText)node, 
						propertyName, propertyValue, multipleRanges, cancelChecker);

				// The list should contain header and two or more extracted properties
				if (multipleRanges.size() > 1) {
					// Extract all values as new property in current module
					List<TextEdit> multipleTextEdits = new ArrayList<>();
					multipleTextEdits.add(headerTextEdit);
					multipleRanges.stream().forEach(r -> {
						multipleTextEdits.add(new TextEdit(r, "${" + propertyName + "}"));
					});
					CodeAction multipleExtractCodeAction = CodeActionFactory.replace( 
							"Extract all values as new property in current module", 
							multipleTextEdits, document.getTextDocument(),  null);
					multipleExtractCodeAction.setDiagnostics(Collections.emptyList());
					codeActions.add(multipleExtractCodeAction);

					// Replace all values with existing "${already.existing.property}" property
					exactValueProperties.stream().forEach(p -> {
						List<TextEdit> multipleReplaceTextEdits = new ArrayList<>();
						multipleRanges.stream().forEach(r -> {
							multipleReplaceTextEdits.add(new TextEdit(r, "${" + p + "}"));
						});
						CodeAction multipleReplaceCodeAction = CodeActionFactory.replace( 
								"Replace all values with existing \"$" + p + "}\" property", 
								multipleReplaceTextEdits, document.getTextDocument(),  null);
						multipleReplaceCodeAction.setDiagnostics(Collections.emptyList());
						codeActions.add(multipleReplaceCodeAction);
					});
	
					// Extract all values as new property in parent ggg:aaa:vvv
					parentProjects.stream().forEach(p -> {
						TextDocumentEdit projectHeaderEdit = headerTextEdits.get(p);
						List<TextEdit> multipleBodyEdits = new ArrayList<>();
						multipleRanges.stream().forEach(r -> {
							multipleBodyEdits.add(new TextEdit(r, "${" + propertyName + "}"));
						});
						if (projectHeaderEdit != null) {
							CodeAction multipleExtractAsNewCodeAction = 
									createReplaceCodeActione("Extract all values as new property in  parent \"" + key(p) + "\"",
									Arrays.asList(projectHeaderEdit, 
											createProjectTextDocumentEdit(document.getTextDocument(), multipleBodyEdits)), null);
							multipleExtractAsNewCodeAction.setDiagnostics(Collections.emptyList());
							codeActions.add(multipleExtractAsNewCodeAction);
						}
					});
				}
			}
		} catch (CancellationException e) {
			throw e;
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
	}
	
	void collectExtractPropertyTextEdits(ICodeActionRequest request, DOMElement rootElement, DOMText text, String property, String value,
			List<Range> ranges, CancelChecker cancelChecker) throws CancellationException {
		cancelChecker.checkCanceled();

		String textElementName = text.getParentElement().getNodeName();
		if (textElementName.equals(rootElement.getNodeName())) {
			Optional<DOMText> rootElementTextNode = rootElement.getChildren().stream()
					.filter(DOMText.class::isInstance).map(DOMText.class::cast)
					.filter(textNode -> value.equals(textNode.getNodeValue()))
					.findFirst();
			rootElementTextNode.stream().map(rootTextNode -> getSingleExtractPropertyRange(request, rootTextNode, property))
				.filter(Objects::nonNull).forEach(ranges::add);
		}
		
		// collect in this element's children 
		rootElement.getChildren().stream().filter(DOMElement.class::isInstance).map(DOMElement.class::cast)
			.forEach(child -> collectExtractPropertyTextEdits(request, child, text, property, value, ranges, cancelChecker));
	}

	private static TextEdit createPropertiesHeaderTextEdit(DOMDocument document, String newline, String indent , 
			String propertyName, String propertyValue, CancelChecker cancelChecker) throws CancellationException {
		DOMElement root = DOMUtils.findChildElement(document.getDocumentElement(), "properties")
							.orElse(document.getDocumentElement());
		cancelChecker.checkCanceled();
		try {
			// Properties section
			int start =  root.getStartTagCloseOffset() + 1;
			StringBuilder sb = new StringBuilder();
			sb.append(newline);
			if (PROJECT_ELT.equals(root.getNodeName())) {
				// Create a new properties section header
				sb.append(indent).append('<').append(PROPERTIES_ELT).append('>').append(newline);
			}
			// Add property
			sb.append(indent).append(indent)
				.append('<').append(propertyName).append('>').append(propertyValue)
				.append('<').append('/').append(propertyName).append('>');
	
			if (PROJECT_ELT.equals( root.getNodeName())) {
				// Create a new properties section footer
				sb.append(newline).append(indent).append("</").append(PROPERTIES_ELT).append('>').append(newline);
			}
	
			Position startPosition = document.positionAt(start);
			cancelChecker.checkCanceled();
			return new TextEdit(new Range(startPosition, startPosition), sb.toString());
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		return null;
	}

	private static Range getSingleExtractPropertyRange(ICodeActionRequest request, DOMText text, String propertyName) {
		try {
			Position startPosition = request.getDocument().positionAt(text.getStart());
			Position endPosition = request.getDocument().positionAt(text.getEnd());
			return new Range(startPosition, endPosition);
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
			return null;
		}	
	}

	private static String constructPropertyName(MavenProject project, DOMNode node, Map<String, String> properties, CancelChecker cancelChecker) {
		DOMElement parent = node.getParentElement();
		String property = null;
		cancelChecker.checkCanceled();
		
		// Try constructing from an artifact id
		Dependency artifact = ParticipantUtils.getArtifactToSearch(project, node);
		if (artifact != null && artifact.getArtifactId() != null) {
			property = artifact.getArtifactId() + '-' + parent.getNodeName();
		}
		
		// Try constructing from am id (f.i., execution id)
		if (property == null) {
			cancelChecker.checkCanceled();
			DOMElement grarndParent = parent.getParentElement();
			Optional<String> id = DOMUtils.findChildElementText(grarndParent, ID_ELT);
			if (id.isEmpty()) { // Checking only for two steps back
				id = DOMUtils.findChildElementText(grarndParent.getParentNode(), ID_ELT);
			}
			
			if (id.isPresent() && !id.get().isBlank()) {
				property = id.get().trim();
			}
		}
			
		// Search from a parent node
		if (property == null) {
			DOMElement grarndParent = parent.getParentElement();
			property = grarndParent.getNodeName() + '-' + parent.getNodeName();
		}

		int index = 0; 
		while (properties.get(property + (index > 0 ? '-' + index : "")) != null) {
			cancelChecker.checkCanceled();
			index++;
		}
		if (index > 0) {
			property += '-' + index;
		}
		cancelChecker.checkCanceled();
		return clearPropertyName(property);
	}

	private static String clearPropertyName(String value) {
		StringBuilder sb = new StringBuilder();
		value.trim().chars()
			.filter(ch -> Character.isAlphabetic(ch) || Character.isDigit(ch) || ch == '.' || ch == '-') 
			.forEach(ch -> {
				if (ch == '.' || ch == '-') {
					// Do not duplicate these chars
					if(sb.length() > 0 && ch != sb.charAt(sb.length() - 1)) {
						sb.append((char)ch);
					}
				} else {
					sb.append((char)ch);
				}
			});
		return sb.toString();
	}
	
	/*
	 *  Returns the list of parent projects starting from an existing Workspace Root
	 */
	private LinkedHashSet<MavenProject> findParentProjects(MavenProject child) {
		LinkedHashSet<MavenProject> parents = new LinkedHashSet<>();
		if (child != null) {
			LinkedHashSet<URI> workspaceRoots = plugin.getCurrentWorkspaceFolders();
			MavenProject parent = child;
			while ((parent = parent.getParent()) != null) {
				File file = parent.getFile();
				if (file != null && isInWorkspacet(workspaceRoots, 
						ParticipantUtils.normalizedUri(file.toURI().toString()))) {
					parents.add(parent);
				}
			}
		}
		return parents;
	}
	
	private static boolean isInWorkspacet(Set<URI> wsRoots, URI uri) {
		String uriPath = uri.normalize().getPath();
		return wsRoots.stream().map(URI::normalize)
				.filter(u -> u.getScheme().equals(uri.getScheme()))
				.map(URI::getPath).filter(uriPath::startsWith).findAny().isPresent();
	}
	
	private static TextDocumentEdit createProjectTextDocumentEdit(TextDocument textDocument, List<TextEdit> projectTextEdits) {
		VersionedTextDocumentIdentifier projectVersionedTextDocumentIdentifier =
				new VersionedTextDocumentIdentifier(textDocument.getUri(), textDocument.getVersion());
		List<Either<TextEdit, SnippetTextEdit>> edits = projectTextEdits.stream()
				.map(Either::<TextEdit, SnippetTextEdit>forLeft)
				.toList();
		return new TextDocumentEdit(projectVersionedTextDocumentIdentifier, edits);
	}
	
	private static CodeAction createReplaceCodeActione(String title, List<TextDocumentEdit> replace, Diagnostic diagnostic) {
		CodeAction insertContentAction = new CodeAction(title);
		insertContentAction.setKind(CodeActionKind.QuickFix);
		insertContentAction.setDiagnostics(Arrays.asList(diagnostic));

		List<Either<TextDocumentEdit, ResourceOperation>> documentChanges = new ArrayList<>();
		replace.stream().forEach(change -> documentChanges.add(Either.forLeft(change)));
		insertContentAction.setEdit( new WorkspaceEdit(documentChanges));
		return insertContentAction;
	}

	private static boolean checkParentElement(DOMNode node) {
		DOMElement parent = node.getParentElement();
		return parent != null 
				&& (!PARENT_ELT.equals(parent.getNodeName()) 
						|| VERSION_ELT.equals(node.getNodeName()));
	}
}
