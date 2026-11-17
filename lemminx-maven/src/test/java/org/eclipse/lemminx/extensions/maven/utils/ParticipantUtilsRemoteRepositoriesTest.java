/*******************************************************************************
 * Copyright (c) 2026 Aleksandar Kurtakov and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.utils;

import static org.eclipse.lemminx.extensions.maven.DOMConstants.DEPENDENCY_ELT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.extensions.maven.searcher.RemoteCentralRepositorySearcher;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ParticipantUtils#getRemoteRepositories(MavenProject, DOMElement)}.
 */
@SuppressWarnings("deprecation") // MavenProject only accepts the legacy ArtifactRepository
class ParticipantUtilsRemoteRepositoriesTest {

	private static final String CENTRAL_ID = RemoteCentralRepositorySearcher.CENTRAL_REPO.getId();
	private static final String CENTRAL_URL = RemoteCentralRepositorySearcher.CENTRAL_REPO.getUrl();

	@Test
	void testAddsDefaultCentralFirstWhenNotConfigured() {
		MavenProject project = projectWithRepositories(repository("internal", "https://repo.example.com/internal"));

		List<RemoteRepository> repositories = ParticipantUtils.getRemoteRepositories(project, dependencyElement());

		assertEquals(List.of(CENTRAL_ID, "internal"), ids(repositories));
		assertEquals(CENTRAL_URL, repositories.get(0).getUrl());
	}

	@Test
	void testKeepsCentralRedefinedByProject() {
		MavenProject project = projectWithRepositories(repository(CENTRAL_ID, "https://repo.example.com/central"));

		List<RemoteRepository> repositories = ParticipantUtils.getRemoteRepositories(project, dependencyElement());

		assertEquals(List.of(CENTRAL_ID), ids(repositories));
		assertEquals("https://repo.example.com/central", repositories.get(0).getUrl());
	}

	@Test
	void testDoesNotAddCentralWhenMirrored() {
		ArtifactRepository mirror = repository("corporate-mirror", "https://repo.example.com/mirror");
		mirror.setMirroredRepositories(List.of(repository(CENTRAL_ID, CENTRAL_URL)));
		MavenProject project = projectWithRepositories(mirror);

		List<RemoteRepository> repositories = ParticipantUtils.getRemoteRepositories(project, dependencyElement());

		assertEquals(List.of("corporate-mirror"), ids(repositories));
	}

	private static MavenProject projectWithRepositories(ArtifactRepository... repositories) {
		MavenProject project = new MavenProject();
		project.setRemoteArtifactRepositories(List.of(repositories));
		return project;
	}

	private static ArtifactRepository repository(String id, String url) {
		ArtifactRepositoryPolicy policy = new ArtifactRepositoryPolicy(true,
				ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);
		return new MavenArtifactRepository(id, url, new DefaultRepositoryLayout(), policy, policy);
	}

	private static DOMElement dependencyElement() {
		DOMDocument document = DOMParser.getInstance().parse(
				"<project><dependencies><dependency></dependency></dependencies></project>", "file:///pom.xml", null);
		return (DOMElement) DOMUtils.findNodesByLocalName(document, DEPENDENCY_ELT).get(0);
	}

	private static List<String> ids(List<RemoteRepository> repositories) {
		return repositories.stream().map(RemoteRepository::getId).toList();
	}
}
