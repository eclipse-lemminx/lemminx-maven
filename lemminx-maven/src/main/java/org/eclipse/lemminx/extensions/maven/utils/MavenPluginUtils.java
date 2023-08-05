/*******************************************************************************
 * Copyright (c) 2020, 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.utils;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.ARTIFACT_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.EXECUTION_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.GOALS_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.GOAL_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.GROUP_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PLUGIN_ELT;

import java.io.IOException;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.Parameter;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RemoteRepository.Builder;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.MojoParameter;
import org.eclipse.lemminx.services.extensions.IPositionRequest;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

public class MavenPluginUtils {

	private static final Logger LOGGER = Logger.getLogger(MavenPluginUtils.class.getName());

	private MavenPluginUtils() {
		// Utility class, not meant to be instantiated
	}

	public static MarkupContent getMarkupDescription(MojoParameter parameter, MojoParameter parentParameter,
			boolean supportsMarkdown) {
		UnaryOperator<String> toBold = supportsMarkdown ? MarkdownUtils::toBold : UnaryOperator.identity();
		String lineBreak = MarkdownUtils.getLineBreak(supportsMarkdown);

		final String fromParent = toBold.apply("From parent configuration element:") + lineBreak;
		String type = parameter.type != null ? parameter.type : "";
		String expression = parameter.getExpression() != null ? parameter.getExpression() : "(none)";
		String defaultValue = parameter.getDefaultValue() != null ? parameter.getDefaultValue() : "(unset)";
		String description = parameter.getDescription() != null ? parameter.getDescription() : "";

		if (defaultValue.isEmpty() && parentParameter != null && parentParameter.getDefaultValue() != null) {
			defaultValue = fromParent + parentParameter.getDefaultValue();
		}
		if (description.isEmpty() && parentParameter != null) {
			description = fromParent + parentParameter.getDescription();
		}

		description = MarkdownUtils.htmlXMLToMarkdown(description);

		String markdownDescription = description + lineBreak +  toBold.apply("Required: ") + parameter.isRequired() + 
				lineBreak + toBold.apply("Type: ") + type + lineBreak + toBold.apply("Expression: ") + expression + 
				lineBreak + toBold.apply("Default Value: ") + defaultValue;

		return new MarkupContent(supportsMarkdown ? MarkupKind.MARKDOWN : MarkupKind.PLAINTEXT, markdownDescription);
	}

	public static Set<Parameter> collectPluginConfigurationParameters(IPositionRequest request,
			MavenLemminxExtension plugin)
			throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException {
		PluginDescriptor pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(request.getNode(), plugin);
		if (pluginDescriptor == null) {
			return Collections.emptySet();
		}
		List<MojoDescriptor> mojosToConsiderList = pluginDescriptor.getMojos();
		DOMNode executionElementDomNode = DOMUtils.findClosestParentNode(request.getNode(), "execution");
		if (executionElementDomNode != null) {
			Set<String> interestingMojos = executionElementDomNode.getChildren().stream()
					.filter(node -> GOALS_ELT.equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(node -> GOAL_ELT.equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(DOMNode::isText).map(DOMNode::getTextContent).collect(Collectors.toSet());
			mojosToConsiderList = mojosToConsiderList.stream().filter(mojo -> interestingMojos.contains(mojo.getGoal()))
					.collect(Collectors.toList());
		}
		return mojosToConsiderList.stream().flatMap(mojo -> mojo.getParameters().stream())
				.collect(Collectors.toSet());
	}

	public static Set<MojoParameter> collectPluginConfigurationMojoParameters(IPositionRequest request,
			MavenLemminxExtension plugin, CancelChecker cancelChecker)
			throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException {
		cancelChecker.checkCanceled();
		PluginDescriptor pluginDescriptor = null;
		try {
			pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(request.getNode(), plugin);
		} catch (PluginResolutionException | PluginDescriptorParsingException | InvalidPluginDescriptorException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		cancelChecker.checkCanceled();
		if (pluginDescriptor == null) {
			return Collections.emptySet();
		}
		List<MojoDescriptor> mojosToConsiderList = pluginDescriptor.getMojos();
		DOMNode executionElementDomNode = DOMUtils.findClosestParentNode(request.getNode(), EXECUTION_ELT);
		cancelChecker.checkCanceled();
		if (executionElementDomNode != null) {
			Set<String> interestingMojos = executionElementDomNode.getChildren().stream()
					.filter(node -> GOALS_ELT.equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(node -> GOAL_ELT.equals(node.getLocalName())).flatMap(node -> node.getChildren().stream())
					.filter(DOMNode::isText).map(DOMNode::getTextContent).collect(Collectors.toSet());
			mojosToConsiderList = mojosToConsiderList.stream().filter(mojo -> interestingMojos.contains(mojo.getGoal()))
					.collect(Collectors.toList());
		}
		MavenProject project = plugin.getProjectCache().getLastSuccessfulMavenProject(request.getXMLDocument());
		cancelChecker.checkCanceled();
		if (project == null) {
			return Collections.emptySet();
		}
		plugin.getMavenSession().setProjects(Collections.singletonList(project));
		final var finalPluginDescriptor = pluginDescriptor;
		cancelChecker.checkCanceled();
		var result = mojosToConsiderList.stream().flatMap(mojo -> PlexusConfigHelper
				.loadMojoParameters(finalPluginDescriptor, mojo, plugin.getMavenSession(), plugin.getBuildPluginManager())
				.stream()).collect(Collectors.toSet());
		cancelChecker.checkCanceled();
		return result;
	}

	public static RemoteRepository toRemoteRepo(Repository modelRepo) {
		Builder builder = new RemoteRepository.Builder(modelRepo.getId(), modelRepo.getLayout(), modelRepo.getUrl());
		return builder.build();
	}

	public static PluginDescriptor getContainingPluginDescriptor(DOMNode node, MavenLemminxExtension lemminxMavenPlugin)
			throws PluginResolutionException, PluginDescriptorParsingException, InvalidPluginDescriptorException,
				CancellationException {
		return getContainingPluginDescriptor(node, lemminxMavenPlugin, false);
	}

	public static PluginDescriptor getContainingPluginDescriptor(DOMNode node, MavenLemminxExtension lemminxMavenPlugin,
			boolean reThrowPluginDescriptorExceptions) throws PluginResolutionException,
				PluginDescriptorParsingException, InvalidPluginDescriptorException, CancellationException {
		CancelChecker cancelChecker = getCancelChecker(node.getOwnerDocument());
		cancelChecker.checkCanceled();
		MavenProject project = lemminxMavenPlugin.getProjectCache()
				.getLastSuccessfulMavenProject(node.getOwnerDocument());
		if (project == null) {
			return null;
		}
		cancelChecker.checkCanceled();
		DOMNode pluginNode = DOMUtils.findClosestParentNode(node, PLUGIN_ELT);
		if (pluginNode == null) {
			return null;
		}
		Optional<String> groupId = DOMUtils.findChildElementText(pluginNode, GROUP_ID_ELT);
		Optional<String> artifactId = DOMUtils.findChildElementText(pluginNode, ARTIFACT_ID_ELT);
		String pluginKey = "";
		if (groupId.isPresent()) {
			pluginKey += groupId.get();
			pluginKey += ':';
		}
		if (artifactId.isPresent()) {
			pluginKey += artifactId.get();
		}
		cancelChecker.checkCanceled();
		Plugin plugin = findPluginInProject(project, pluginKey, artifactId);

		if (plugin == null) {
			cancelChecker.checkCanceled();
			DOMNode profileNode = DOMUtils.findClosestParentNode(node, "profile");
			if (profileNode != null) {
				cancelChecker.checkCanceled();
				project = profileNode.getChildren().stream() //
						.filter(DOMElement.class::isInstance) //
						.map(DOMElement.class::cast) //
						.filter(n -> "id".equals(n.getLocalName())) //
						.map(n -> n.getChild(0).getTextContent())
						.filter(Objects::nonNull)
						.map(profileId -> lemminxMavenPlugin.getProjectCache().getSnapshotProject(node.getOwnerDocument(), profileId)) //
						.filter(Objects::nonNull)
						.findFirst().orElse(null);
				if (project != null) {
					cancelChecker.checkCanceled();
					plugin = findPluginInProject(project, pluginKey, artifactId);
				}
			}
		}

		cancelChecker.checkCanceled();
		if (plugin == null) {
			throw new InvalidPluginDescriptorException("Unable to resolve " + pluginKey, Collections.emptyList());
		}
		PluginDescriptor pluginDescriptor = null;
		try {
			// Fix plugin version in case its value is 'null', to initiate search for an actual version
			if (plugin.getVersion() == null) {
				plugin.setVersion("0.0.1-SNAPSHOT");
			}
			
			pluginDescriptor = lemminxMavenPlugin.getMavenPluginManager().getPluginDescriptor(plugin,
				project.getRemotePluginRepositories().stream().collect(Collectors.toList()),
				lemminxMavenPlugin.getMavenSession().getRepositorySession());
		} catch (PluginResolutionException | PluginDescriptorParsingException | InvalidPluginDescriptorException ex) {
			LOGGER.log(Level.WARNING, ex.getMessage(), ex);
			if (reThrowPluginDescriptorExceptions) {
				throw ex; // Needed for plugin validation
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, "An error occured while getting a plugin descriptor: " + e.getMessage(), e);
		}
		
		cancelChecker.checkCanceled();
		if (pluginDescriptor == null && "0.0.1-SNAPSHOT".equals(plugin.getVersion())) { // probably missing or not parsed version
			final Plugin thePlugin = plugin;			
			Optional<DefaultArtifactVersion> version = lemminxMavenPlugin.getLocalRepositorySearcher().getLocalArtifactsLastVersion().stream()
				.filter(gav -> thePlugin.getArtifactId().equals(gav.getArtifactId()))
				.filter(gav -> thePlugin.getGroupId().equals(gav.getGroupId()))
				.map(Artifact::getVersion) //
				.map(DefaultArtifactVersion::new) //
				.collect(Collectors.maxBy(Comparator.naturalOrder()));			
			
			cancelChecker.checkCanceled();
			if (version.isPresent()) {
				plugin.setVersion(version.get().toString());
				try {
					pluginDescriptor = lemminxMavenPlugin.getMavenPluginManager().getPluginDescriptor(plugin,
							project.getRemotePluginRepositories().stream().collect(Collectors.toList()),
							lemminxMavenPlugin.getMavenSession().getRepositorySession());
				} catch (PluginResolutionException | PluginDescriptorParsingException | InvalidPluginDescriptorException ex) {
					LOGGER.log(Level.WARNING, ex.getMessage(), ex);
					if (reThrowPluginDescriptorExceptions) {
						throw ex; // Needed for plugin validation
					}
				} catch (Exception e) {
					LOGGER.log(Level.SEVERE, "An error occured while getting a plugin descriptor: " + e.getMessage(), e);
				}
			}
		}
		cancelChecker.checkCanceled();
		return pluginDescriptor;
	}

	private static Plugin findPluginInProject(MavenProject project, String pluginKey, Optional<String> artifactId) {
		Optional<Plugin> plugin = Optional.ofNullable(project.getPlugin(pluginKey))
				.or(() -> Optional.ofNullable(project.getPluginManagement().getPluginsAsMap().get(pluginKey)));
		if (artifactId.isPresent()) {
			plugin = plugin.or(() ->
				Stream.concat(project.getBuildPlugins().stream(), project.getPluginManagement().getPlugins().stream())
						.filter(p -> artifactId.get().equals(p.getArtifactId()))
						.findFirst()
			);
		}
		return plugin.orElse(null);
	}
	
	private static CancelChecker getCancelChecker(DOMDocument document) {
		return Optional.ofNullable(document)
				.filter(Objects::nonNull)
				.map(DOMDocument::getCancelChecker)
				.orElse(() -> {});
	}
}
