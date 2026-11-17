/*******************************************************************************
 * Copyright (c) 2020, 2026 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.participants.definition;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.MODULE_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PARENT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PROPERTIES_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.RELATIVE_PATH_ELT;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

import org.apache.maven.Maven;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.InputLocation;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.extensions.maven.MavenInitializationException;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.MavenModelOutOfDatedException;
import org.eclipse.lemminx.extensions.maven.utils.DOMUtils;
import org.eclipse.lemminx.extensions.maven.utils.ParticipantUtils;
import org.eclipse.lemminx.services.extensions.IDefinitionParticipant;
import org.eclipse.lemminx.services.extensions.IDefinitionRequest;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public class MavenDefinitionParticipant implements IDefinitionParticipant {

	private final MavenLemminxExtension plugin;

	public MavenDefinitionParticipant(MavenLemminxExtension plugin) {
		this.plugin = plugin;
	}

	@SuppressWarnings("deprecation")
	@Override
	public void findDefinition(IDefinitionRequest request, List<LocationLink> locations, CancelChecker cancelChecker) {
		if (!MavenLemminxExtension.match(request.getXMLDocument())) {
			return;
		}
		try {
			// make sure model is resolved and up-to-date
			File currentFolder = new File(URI.create(request.getXMLDocument().getTextDocument().getUri())).getParentFile();
			
			LocationLink propertyLocation = findMavenPropertyLocation(request, cancelChecker);
			if (propertyLocation != null) {
				cancelChecker.checkCanceled();
				locations.add(propertyLocation);
				return;
			}
	
			cancelChecker.checkCanceled();
			DOMElement element = ParticipantUtils.findInterestingElement(request.getNode());
			if (element == null) {
				return;
			}
			if (MODULE_ELT.equals(element.getLocalName())) {
				File subModuleFile = new File(currentFolder,
						element.getFirstChild().getTextContent() + File.separator + Maven.POMv4);
				if (subModuleFile.isFile()) {
					cancelChecker.checkCanceled();
					locations.add(toLocationNoRange(subModuleFile, element));
				}
				return;
			}
			
			cancelChecker.checkCanceled();
			MavenProject p = plugin.getProjectCache().getLastSuccessfulMavenProject(element.getOwnerDocument());
			Dependency dependency = ParticipantUtils.getArtifactToSearch(p, request.getNode());
	
			cancelChecker.checkCanceled();
			DOMNode parentNode = DOMUtils.findClosestParentNode(request.getNode(), PARENT_ELT);
			if (parentNode != null && parentNode.isElement()) {
				// Find in workspace
				if (ParticipantUtils.isWellDefinedDependency(dependency)) {
					cancelChecker.checkCanceled();
					
					LocationLink location = null;
					Optional<Dependency> managed = ParticipantUtils.findManagedDependency(p, dependency);
					if (managed.isPresent()) {
						location = toLocation(managed.get().getLocation("artifactId"), 
								XMLPositionUtility.createRange(element));
					} else {
						Artifact artifact = ParticipantUtils.findWorkspaceArtifact(plugin, request, dependency);
						if (artifact != null && artifact.getFile() != null) {
							location = toLocationNoRange(artifact.getFile(), element);
						}
					}
					if (location != null) {
						cancelChecker.checkCanceled();
						locations.add(location);
						return;
					}
				}
				
				cancelChecker.checkCanceled();
				Optional<String> relativePath = DOMUtils.findChildElementText(element, RELATIVE_PATH_ELT);
				if (relativePath.isPresent()) {
					File relativeFile = new File(currentFolder, relativePath.get());
					if (relativeFile.isDirectory()) {
						relativeFile = new File(relativeFile, Maven.POMv4);
					}
					if (relativeFile.isFile()) {
						cancelChecker.checkCanceled();
						locations.add(toLocationNoRange(relativeFile, parentNode));
						return;
					}
				} else {
					File relativeFile = new File(currentFolder.getParentFile(), Maven.POMv4);
					if (match(relativeFile, dependency)) {
						cancelChecker.checkCanceled();
						locations.add(toLocationNoRange(relativeFile, parentNode));
						return;
					} else {
						// those next lines may actually be more generic and suit parent definition in any case
						MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(request.getXMLDocument());
						if (project != null && project.getParentFile() != null) {
							cancelChecker.checkCanceled();
							locations.add(toLocationNoRange(project.getParentFile(), parentNode));
							return;
						}
					}
				}
			}
	
			cancelChecker.checkCanceled();
			dependency = ParticipantUtils.resolveDependency(p, dependency, element, plugin);
			if (dependency != null) {
				cancelChecker.checkCanceled();
				// Find in workspace
				if (ParticipantUtils.isWellDefinedDependency(dependency)) {
					LocationLink location = null;
					Optional<Dependency> managed = ParticipantUtils.isManagedDependency(element) ?
							Optional.empty() : ParticipantUtils.findManagedDependency(p, dependency);
					if (managed.isPresent()) {
						location = toLocation(managed.get().getLocation("artifactId"), 
								XMLPositionUtility.createRange(element));
					} else {
						Artifact artifact = ParticipantUtils.findWorkspaceArtifact(plugin, request, dependency);
						if (artifact != null && artifact.getFile() != null) {
							location = toLocationNoRange(artifact.getFile(), element);
						}
					}
					if (location != null) {
						cancelChecker.checkCanceled();
						locations.add(location);
						return;
					}
				}
				
				cancelChecker.checkCanceled();
				// Find in local repository
				File localArtifactLocation = plugin.getLocalRepositorySearcher().findLocalFile(dependency);
				if (localArtifactLocation != null && localArtifactLocation.isFile()) {
					cancelChecker.checkCanceled();
					locations.add(toLocationNoRange(localArtifactLocation, element));
					return;
				}
			}
		} catch (MavenInitializationException | MavenModelOutOfDatedException e) {
			// - Maven is initializing
			// - or parse of maven model with DOM document is out of dated
			// -> catch the error to avoid breaking XML definition from LemMinX
		}
	}
	
	private LocationLink findMavenPropertyLocation(IDefinitionRequest request, CancelChecker cancelChecker) throws CancellationException{
		cancelChecker.checkCanceled();
		Map.Entry<Range, String> mavenProperty = ParticipantUtils.getMavenPropertyInRequest(request);
		if (mavenProperty == null) {
			return null;
		}

		cancelChecker.checkCanceled();
		DOMDocument xmlDocument = request.getXMLDocument();
		MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(xmlDocument);
		if (project == null) {
			return null;
		}
		
		MavenProject childProj = project;
		while (project != null && project.getProperties().containsKey(mavenProperty.getValue())) {
			cancelChecker.checkCanceled();
			childProj = project;
			project = project.getParent();
		}

		DOMNode propertyDeclaration = null;
		Predicate<DOMNode> isMavenProperty = (node) -> PROPERTIES_ELT.equals(node.getParentNode().getLocalName());
		URI childProjectUri = ParticipantUtils.normalizedUri(childProj.getFile().toURI().toString());
		URI thisProjectUri = ParticipantUtils.normalizedUri(xmlDocument.getDocumentURI());
		cancelChecker.checkCanceled();
		if (childProjectUri.equals(thisProjectUri)) {
			// Property is defined in the same file as the request
			propertyDeclaration = DOMUtils.findNodesByLocalName(xmlDocument, mavenProperty.getValue()).stream()
					.filter(isMavenProperty).findFirst().orElse(null);
		} else {
			DOMDocument propertyDeclaringDocument = org.eclipse.lemminx.utils.DOMUtils.loadDocument(
					childProj.getFile().toURI().toString(),
					request.getNode().getOwnerDocument().getResolverExtensionManager());
			cancelChecker.checkCanceled();
			propertyDeclaration = DOMUtils.findNodesByLocalName(propertyDeclaringDocument, mavenProperty.getValue())
					.stream().filter(isMavenProperty).findFirst().orElse(null);
		}

		if (propertyDeclaration == null) {
			return null;
		}

		cancelChecker.checkCanceled();
		return toLocation(childProj.getFile(), propertyDeclaration, mavenProperty.getKey());
	}

	private boolean match(File relativeFile, Dependency dependency) {
		return plugin.getProjectCache().getSnapshotProject(relativeFile)
				.filter(p -> p.getGroupId().equals(dependency.getGroupId()) && //
						p.getArtifactId().equals(dependency.getArtifactId()) && //
						p.getVersion().equals(dependency.getVersion()))
				.isPresent();
	}

	private static LocationLink toLocationNoRange(File target, DOMNode originNode) {
		if (target == null) {
			return null;
		}
		Range dumbRange = new Range(new Position(0, 0), new Position(0, 0));
		return new LocationLink(target.toURI().toString(), dumbRange, dumbRange,
				XMLPositionUtility.createRange(originNode));
	}

	private static LocationLink toLocation(File target, DOMNode targetNode, Range originRange) {
		Range targetRange = XMLPositionUtility.createRange(targetNode);
		return new LocationLink(target.toURI().toString(), targetRange, targetRange, originRange);
	}
	
	private static LocationLink toLocation(InputLocation target, Range originRange) {
		if (target == null || target.getSource() == null || target.getSource().getLocation() == null) {
			return null;
		}
		Position position = new Position(target.getLineNumber(), target.getColumnNumber());
		Range targetRange = new Range(position, position);
		File file = new File(target.getSource().getLocation());
		return new LocationLink(file.toURI().toString(), targetRange, targetRange, originRange);
	}
}