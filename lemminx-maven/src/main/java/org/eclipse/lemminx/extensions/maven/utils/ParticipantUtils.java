/*******************************************************************************
 * Copyright (c) 2022, 2026 Red Hat Inc. and others.
 *
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Victor Rubezhny (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.utils;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.ARTIFACT_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.DEPENDENCIES_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.DEPENDENCY_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.DEPENDENCY_MANAGEMENT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.GROUP_ID_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.MODULE_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PARENT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PLUGIN_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.PROJECT_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.RELATIVE_PATH_ELT;
import static org.eclipse.lemminx.extensions.maven.DOMConstants.VERSION_ELT;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang3.Validate;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.plugin.InvalidPluginDescriptorException;
import org.apache.maven.plugin.PluginDescriptorParsingException;
import org.apache.maven.plugin.PluginResolutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.properties.internal.EnvironmentUtils;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.impl.ArtifactResolver;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.searcher.RemoteCentralRepositorySearcher;
import org.eclipse.lemminx.services.extensions.IPositionRequest;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Range;

public class ParticipantUtils {
	private static final Logger LOGGER = Logger.getLogger(ParticipantUtils.class.getName());

	private static Properties environmentProperties = null;
	
	public static Properties getEnvironmentProperties() {
		if (environmentProperties == null) {
			Properties properties = new Properties();
			EnvironmentUtils.addEnvVars(properties);
			environmentProperties = properties;
		}
		return environmentProperties;
	}
	
	public static DOMElement findInterestingElement(DOMNode node) {
		if (node == null) {
			return null;
		}
		if (!node.isElement()) {
			return findInterestingElement(node.getParentElement());
		}
		DOMElement element = (DOMElement) node;
		switch (node.getLocalName()) {
		case MODULE_ELT:
			return element;
		case ARTIFACT_ID_ELT:
		case GROUP_ID_ELT:
		case VERSION_ELT:
		case RELATIVE_PATH_ELT:
			return node.getParentElement();
		default:
			// continue
		}
		if (DOMUtils.findChildElementText(element, ARTIFACT_ID_ELT).isPresent()) {
			return element;
		}
		return null;
	}
	
	public static Dependency resolveDependency (MavenProject project, Dependency dependency, DOMElement element,
			MavenLemminxExtension plugin) {
		if (dependency == null || element == null) {
			return null;
		}

		if (isWellDefinedDependency(dependency)) {
			return dependency;
		}

		if (isPlugin(element)) {
			try {
				PluginDescriptor pluginDescriptor = MavenPluginUtils.getContainingPluginDescriptor(element, plugin);
				if (pluginDescriptor != null) {
					dependency.setGroupId(pluginDescriptor.getGroupId());
					dependency.setArtifactId(pluginDescriptor.getArtifactId());
					dependency.setVersion(pluginDescriptor.getVersion());
				}					
			} catch (PluginResolutionException | PluginDescriptorParsingException
					| InvalidPluginDescriptorException e) {
				// Ignore
			} catch (Exception e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
			}
		} else if (isDependency(element)) {
			if (dependency.getGroupId() == null || dependency.getVersion() == null) {
				if (project != null) {
					final Dependency originalDependency = dependency;
					dependency = project.getDependencies().stream()
							.filter(dep -> (originalDependency.getGroupId() == null
									|| originalDependency.getGroupId().equals(dep.getGroupId())
											&& (originalDependency.getArtifactId() == null
													|| originalDependency.getArtifactId().equals(dep.getArtifactId()))
											&& (originalDependency.getVersion() == null
													|| originalDependency.getVersion().equals(dep.getVersion()))))
							.findFirst().orElse(dependency);
				}
			}
		} else if (isProject(element)) {
			DOMNode projectNode = DOMUtils.findClosestParentNode(element, PROJECT_ELT);
			if (projectNode != null) {
				Optional<DOMElement> parent = DOMUtils.findChildElement(projectNode, PARENT_ELT);
				if (parent.isPresent()) {
					if (dependency.getGroupId() == null) {
						dependency.setGroupId(DOMUtils.findChildElementText(parent.get(), GROUP_ID_ELT).orElse(null));
					}
					if (dependency.getVersion() == null) {
						dependency.setVersion(DOMUtils.findChildElementText(parent.get(), VERSION_ELT).orElse(null));
					}
				}
			}
		}
		return dependency;
	}
	
	public static Artifact findWorkspaceArtifact(MavenLemminxExtension plugin, IPositionRequest request, Dependency artifactToSearch) {
		// Here we can search only if all artifact is well defined
		if (!isWellDefinedDependency(artifactToSearch)) {
			return null;
		}
		
		DOMElement element = findInterestingElement(request.getNode());
		MavenProject p = plugin.getProjectCache().getLastSuccessfulMavenProject(request.getXMLDocument());
        List<RemoteRepository> remoteRepositories = getRemoteRepositories(p, element);
		try {
			ArtifactResult result = plugin.getPlexusContainer().lookup(ArtifactResolver.class)
					.resolveArtifact(plugin.getMavenSession().getRepositorySession(), 
							new ArtifactRequest(
									new DefaultArtifact(
										artifactToSearch.getGroupId(), artifactToSearch.getArtifactId(), 
										isPlugin(element) ? "pom" : null, artifactToSearch.getVersion()), 
									remoteRepositories, null));
			if (result !=null) {
				return result.getArtifact();
			}
		} catch (CancellationException e) {
			throw e;
		} catch (ArtifactResolutionException | ComponentLookupException e) {
			// can happen
			LOGGER.log(Level.FINEST, e.getMessage(), e);
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		return null;
	}

	public static List<RemoteRepository> getRemoteRepositories(MavenProject pproject, DOMElement element) {
		List<RemoteRepository> remoteRepositories = new ArrayList<RemoteRepository>(
				isPlugin(element) ? pproject.getRemotePluginRepositories()
						: isDependency(element) ? pproject.getRemoteProjectRepositories() : List.of());
		// Keep a central repository configured by the project (e.g. with an internal URL
		// or other policies) or a mirror of it, and add the default one only if there is
		// none. Compare by id, RemoteRepository.equals() also compares the URL and the
		// policies.
		String centralId = RemoteCentralRepositorySearcher.CENTRAL_REPO.getId();
		if (remoteRepositories.stream().noneMatch(r -> servesRepository(r, centralId))) {
			remoteRepositories.add(0, RemoteCentralRepositorySearcher.CENTRAL_REPO);
		}
		return remoteRepositories;
	}

	private static boolean servesRepository(RemoteRepository repository, String id) {
		return id.equals(repository.getId())
				|| repository.getMirroredRepositories().stream().anyMatch(mirrored -> id.equals(mirrored.getId()));
	}
	
	public static boolean isDependency(DOMElement element) {
		return DEPENDENCY_ELT.equals(element.getLocalName()) || 
				(element.getParentElement() != null && DEPENDENCY_ELT.equals(element.getParentElement().getLocalName()));
	}

	public static boolean isManagedDependency(DOMElement element) {
		DOMElement parent = element;
		// Find dependency element
		while (parent != null &&  !DEPENDENCY_ELT.equals(parent.getLocalName())) {
			parent = parent.getParentElement();
		}
		if (parent == null) {
			return false;
		}
		// Check if parent is dependencies element
		parent = parent.getParentElement();
		if (parent == null || !DEPENDENCIES_ELT.equals(parent.getLocalName())) {
			return false;
		}
		// Check if parent is dependencyManagement element
		parent = parent.getParentElement();
		return (parent != null  && DEPENDENCY_MANAGEMENT_ELT.equals(parent.getLocalName()));
	}
	
	public static boolean isPlugin(DOMElement element) {
		return  PLUGIN_ELT.equals(element.getLocalName()) || 
					(element.getParentElement() != null && PLUGIN_ELT.equals(element.getParentElement().getLocalName()));
	}

	public static boolean isProject(DOMElement element) {
		return  PROJECT_ELT.equals(element.getLocalName()) || 
					(element.getParentElement() != null && PROJECT_ELT.equals(element.getParentElement().getLocalName()));
	}
	
	public static boolean isParentDeclaration(DOMElement element) {
		return  PARENT_ELT.equals(element.getLocalName()) || 
					(element.getParentElement() != null && PARENT_ELT.equals(element.getParentElement().getLocalName()));
	}

	public static Dependency getArtifactToSearch(MavenProject pproject, DOMNode node) {
		Dependency artifactToSearch = MavenParseUtils.parseArtifact(node);
		if (artifactToSearch != null) {
			if (artifactToSearch.getGroupId() != null && artifactToSearch.getGroupId().contains("$")) {
				artifactToSearch = artifactToSearch.clone();
				artifactToSearch.setGroupId(resolveValueWithProperties(pproject, artifactToSearch.getGroupId()));
			}
			if (artifactToSearch.getArtifactId() != null && artifactToSearch.getArtifactId().contains("$")) {
				artifactToSearch = artifactToSearch.clone();
				artifactToSearch.setArtifactId(resolveValueWithProperties(pproject, artifactToSearch.getArtifactId()));
			}
			if (artifactToSearch.getVersion() != null && artifactToSearch.getVersion().contains("$")) {
				artifactToSearch = artifactToSearch.clone();
				artifactToSearch.setVersion(resolveValueWithProperties(pproject, artifactToSearch.getVersion()));
			}
		}
		return artifactToSearch;
	}
	
	public static String resolveValueWithProperties(MavenProject project, String value) {
		Map<String, String> properties = getMavenProjectProperties(project);
		
		StringBuilder sb = new StringBuilder();
		int index = -1;
		int closeIndex = -1;
		int start = 0;
		while((index = value.indexOf("${", start)) != -1 && 
				(closeIndex = value.indexOf("}", start)) != -1) {
			sb.append(value.substring(start, index));
			String propertyName = value.substring(index + 2, closeIndex);
			String propertyValue = properties.get(propertyName);
			if (propertyValue != null) {
				sb.append(propertyValue);
			}
			start = closeIndex + 1;
		}
		sb.append(value.substring(start));
		
		return sb.length() == 0 ? null : sb.toString();
	}
	
	public static Map<String, String> getMavenProjectProperties(MavenProject project) {
		if (project == null) {
			return Map.of();
		}
		// See org.apache.maven.plugin.PluginParameterExpressionEvaluator
		Map<String, String> allProps = new HashMap<>();
		putProperties(getEnvironmentProperties(), allProps);
		putProperties(project.getProperties(), allProps);
		ProjectBuildingRequest buildingRequest = project.getProjectBuildingRequest();
		if (buildingRequest != null) {
			// user properties overwrite project properties
			putProperties(buildingRequest.getUserProperties(), allProps);
			// but system properties overwrite even user properties
			putProperties(buildingRequest.getSystemProperties(), allProps);
		}
		allProps.put("basedir", project.getBasedir().toString());
		allProps.put("project.basedir", project.getBasedir().toString());
		allProps.put("project.version", project.getVersion());
		allProps.put("project.groupId", project.getGroupId());
		allProps.put("project.artifactId", project.getArtifactId());
		allProps.put("project.name", project.getName());
		Build build = project.getBuild();
		if (build != null) {
			allProps.put("project.build.directory", build.getDirectory());
			allProps.put("project.build.outputDirectory", build.getOutputDirectory());
			allProps.put("project.build.testOutputDirectory", build.getTestOutputDirectory());
		}
		return allProps;
	}

	private static void putProperties(Properties properties, Map<String, String> map) {
		if (properties == null || map == null) {
			return;
		}
		properties.stringPropertyNames().forEach(envProperty -> {
			map.put(envProperty, properties.getProperty(envProperty));
		});
	}

	public static Map.Entry<Range, String> getMavenPropertyInRequest(IPositionRequest request) {
		return getMavenProperty(request.getNode(), request.getOffset());
	}
	
	public static Map.Entry<Range, String> getMavenProperty(DOMNode tag, int offset) {
		DOMElement element = tag.isElement() ? (DOMElement)tag : tag.getParentElement();
		String tagText = DOMUtils.findElementText(element).orElse(null);
		if (tagText == null || tagText.indexOf("${") == -1) {
			return null;
		}

		int propertyOffset = tag.getStart();
		int beforeHover = offset - propertyOffset;
		if (beforeHover > tagText.length()) {
			return null;
		}
		
		try {
			String beforeHoverText = tagText.substring(0, beforeHover);
			String afterHoverText = tagText.substring(beforeHover);
	
			int indexOpen = beforeHoverText.lastIndexOf("${");
			int indexCloseBefore = beforeHoverText.lastIndexOf('}');
			if (indexOpen < indexCloseBefore) {
				return null; // outside of the maven property use 
			}
			int indexCloseAfter = afterHoverText.indexOf('}');
			if (indexCloseAfter == -1) {
				return null; //incomplete maven property use
			}
			if (indexOpen > indexCloseBefore) {
				String propertyText = tagText.substring(indexOpen + 2, indexCloseAfter + beforeHover);
				int textStart = tag.getStart();
				Range propertyRange = XMLPositionUtility.createRange(textStart + indexOpen + 2,
						textStart + beforeHover + indexCloseAfter, tag.getOwnerDocument());
				return Map.entry(propertyRange, propertyText);
			}
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.toString(), e);
		}
		return null;
	}
	
	public static boolean isWellDefinedDependency(Dependency dependency) {
		try {
			notNullNorBlank(dependency.getGroupId());
			notNullNorBlank(dependency.getArtifactId());
			notNullNorBlank(dependency.getVersion());
		} catch (Exception e) {
			return false;
		}
		return true;
	}
	
	private static void notNullNorBlank(String str) {
        int c = str != null && str.length() > 0 ? str.charAt( 0 ) : 0;
        if ( ( c < '0' || c > '9' ) && ( c < 'a' || c > 'z' ) )
        {
            Validate.notBlank( str, "Argument can neither be null, empty nor blank" );
        }
    }
	
	/**
	 * Matches the provided Diagnostic with the specified code.
	 * 
	 * @param diagnostic 
	 * @param code
	 * @return Returns true in case the code matches the Diagnostics code (even if both 
	 * 			values are null),, otherwise returns false
	 */
	public static boolean match(Diagnostic diagnostic, String code) {
		if (diagnostic == null || diagnostic.getCode() == null || !diagnostic.getCode().isLeft()) {
			return false;
		}
		
		return code == null ? diagnostic.getCode().getLeft() == null :
				code.equals(diagnostic.getCode().getLeft());
	}
	
	public static URI normalizedUri(String uriString) {
		try {
			return URI.create(uriString).normalize();
		} catch (IllegalArgumentException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
			return null;
		}
	}
	
	public static String getDocumentLineSeparator(DOMDocument document) {
		String lineDelimiter = System.lineSeparator();
		try {
			lineDelimiter = document.lineDelimiter(0);
		} catch (BadLocationException e) {
			LOGGER.log(Level.SEVERE, "Unable to get document line delimiter", e);
		}
		return (lineDelimiter == null || lineDelimiter.isEmpty()) 
				? System.lineSeparator() : lineDelimiter;
	}
	
	public static Optional<Dependency> findManagedDependency(MavenProject project, Dependency dependency) {
		// Search in Managed Demendencies
		// collect the managed dep ids
		List<Dependency> managed = new  ArrayList<>();
		DependencyManagement dm = project.getDependencyManagement();
		if (dm != null) {
			List<Dependency> deps = dm.getDependencies();
			if (deps != null) {
				// #335366
				// 355882 use dep.getManagementKey() to prevent false positives
				// when type or classifier doesn't match
				deps.stream()
				.filter(dep -> dep.getLocation("artifactId") != null)
				.filter(dep -> dependency.getGroupId() != null && dependency.getGroupId().equals(dep.getGroupId()))
				.filter(dep -> dependency.getArtifactId() != null && dependency.getArtifactId().equals(dep.getArtifactId()))
				.filter(dep -> dependency.getVersion() != null && dependency.getVersion().equals(dep.getVersion()))
				.forEach(managed::add);
			}
		}
		
		return managed.stream()
				.sorted(new Comparator<Dependency>() {
				// Backward order
				@Override
				public int compare(Dependency o1, Dependency o2) {
					return new DefaultArtifactVersion(o2.getVersion())
							.compareTo(new DefaultArtifactVersion(o1.getVersion()));
				}
			}).findFirst();
	}
}
