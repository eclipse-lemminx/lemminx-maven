/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.searcher;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.Indexer;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.SearchType;
import org.apache.maven.index.context.IndexCreator;
import org.apache.maven.index.context.IndexingContext;
import org.apache.maven.index.updater.IndexUpdateRequest;
import org.apache.maven.index.updater.IndexUpdateResult;
import org.apache.maven.index.updater.IndexUpdater;
import org.apache.maven.index.updater.ResourceFetcher;
import org.apache.maven.index.updater.WagonHelper;
import org.apache.maven.model.Dependency;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.observers.AbstractTransferListener;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;

public class RemoteRepositoryIndexSearcher {

	private static final Logger LOGGER = Logger.getLogger(RemoteRepositoryIndexSearcher.class.getName());

	private static final String PACKAGING_TYPE_JAR = "jar";
	private static final String PACKAGING_TYPE_MAVEN_PLUGIN = "maven-plugin";

	public static final RemoteRepository CENTRAL_REPO = new RemoteRepository.Builder("central", "default",
			"https://repo.maven.apache.org/maven2").build();

	public static boolean disableCentralIndex = Boolean
			.parseBoolean(System.getProperty(RemoteRepositoryIndexSearcher.class.getName() + ".disableCentralIndex"));

	private Indexer indexer;

	private IndexUpdater indexUpdater;

	private List<IndexCreator> indexers = new ArrayList<>();

	private final File indexPath;

	private Map<URI, IndexingContext> indexingContexts = new HashMap<>();
	private Map<IndexingContext, CompletableFuture<IndexingContext>> indexDownloadJobs = new HashMap<>();

	private final PlexusContainer plexusContainer;

	public RemoteRepositoryIndexSearcher(MavenLemminxExtension lemminxMavenPlugin, PlexusContainer plexusContainer,
			Optional<File> configuredIndexLocation) {
		this.plexusContainer = plexusContainer;
		indexPath = Optional.ofNullable(System.getProperty("lemminx.maven.indexDirectory")).filter(Objects::nonNull)
				.map(String::trim).filter(Predicate.not(String::isEmpty)).map(File::new)
				.or(() -> configuredIndexLocation)
				.orElse(new File(lemminxMavenPlugin.getMavenSession().getLocalRepository().getBasedir() + "/index"));
		indexPath.mkdirs();
		try {
			indexer = plexusContainer.lookup(Indexer.class);
			indexUpdater = plexusContainer.lookup(IndexUpdater.class);
			indexers.add(plexusContainer.lookup(IndexCreator.class, "min"));
			indexers.add(plexusContainer.lookup(IndexCreator.class, "jarContent"));
			indexers.add(plexusContainer.lookup(IndexCreator.class, PACKAGING_TYPE_MAVEN_PLUGIN));
		} catch (Exception e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
	}

	public CompletableFuture<IndexingContext> getIndexingContext(URI repositoryUrl) {
//		if (!repositoryUrl.toString().endsWith("/")) {
//			repositoryUrl = URI.create(repositoryUrl.toString() + "/");
//		}
		synchronized (indexingContexts) {
			IndexingContext res = indexingContexts.get(repositoryUrl);
			if (res != null && indexDownloadJobs.containsKey(res)) {
				final IndexingContext context = res;
				return indexDownloadJobs.get(res).thenApply(theVoid -> context);
			}
			final IndexingContext context = initializeContext(repositoryUrl);
			if (context != null) {
				indexingContexts.put(repositoryUrl, context);
				CompletableFuture<IndexingContext> future;
				try {
					future = updateIndex(context).thenApply(theVoid -> context);
					indexDownloadJobs.put(context, future);
					return future;
				} catch (ComponentLookupException e) {
					return CompletableFuture.failedFuture(e);
				}
			} else {
				return CompletableFuture.completedFuture(null);
			}
		}
	}

	private Set<ArtifactVersion> internalGetArtifactVersions(Dependency artifactToSearch, String packaging,
			IndexingContext... requestSpecificContexts) {
		if (artifactToSearch.getArtifactId() == null || artifactToSearch.getArtifactId().trim().isEmpty()) {
			return Collections.emptySet();
		}
		BooleanQuery.Builder builder = new BooleanQuery.Builder();
		if (artifactToSearch.getGroupId() != null) {
			builder.add(indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.EXACT),
					Occur.MUST);
		}
		builder.add(indexer.constructQuery(MAVEN.ARTIFACT_ID, artifactToSearch.getArtifactId(), SearchType.EXACT),
				Occur.SHOULD);
		builder.add(indexer.constructQuery(MAVEN.PACKAGING, packaging, SearchType.EXACT), Occur.MUST);
		final BooleanQuery query = builder.build();

		List<IndexingContext> contexts = internalGetIndexingContexts(requestSpecificContexts);;
		final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, null);

		return createIndexerQuery(artifactToSearch, request).stream().map(ArtifactInfo::getVersion)
				.map(DefaultArtifactVersion::new).sorted().collect(Collectors.toSet());
	}

	public Set<ArtifactVersion> getArtifactVersions(Dependency artifactToSearch,
			IndexingContext... requestSpecificContexts) {
		return internalGetArtifactVersions(artifactToSearch, PACKAGING_TYPE_JAR, requestSpecificContexts);
	}

	public Set<ArtifactVersion> getPluginArtifactVersions(Dependency artifactToSearch,
			IndexingContext... requestSpecificContexts) {
		return internalGetArtifactVersions(artifactToSearch, PACKAGING_TYPE_MAVEN_PLUGIN, requestSpecificContexts);
	}

	private Collection<ArtifactInfo> internalGetArtifacts(Dependency artifactToSearch, String packaging,
			IndexingContext... requestSpecificContexts) {
		BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
		if (artifactToSearch.getGroupId() != null) {
			queryBuilder.add(indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.EXACT),
					Occur.MUST);
		}
		if (artifactToSearch.getArtifactId() != null) {
			queryBuilder.add(
					indexer.constructQuery(MAVEN.ARTIFACT_ID, artifactToSearch.getArtifactId(), SearchType.EXACT),
					Occur.MUST);
		}
		queryBuilder.add(indexer.constructQuery(MAVEN.PACKAGING, packaging, SearchType.EXACT), Occur.MUST);
		final BooleanQuery query = queryBuilder.build();
		List<IndexingContext> contexts = internalGetIndexingContexts(requestSpecificContexts);;
		final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, null);
		return createIndexerQuery(artifactToSearch, request);
	}

	/**
	 * @param artifactToSearch a CompletableFuture containing a
	 *                         {@code Map<String artifactId, String artifactDescription>}
	 * @return
	 */
	public Collection<ArtifactInfo> getArtifacts(Dependency artifactToSearch,
			IndexingContext... requestSpecificContexts) {
		return internalGetArtifacts(artifactToSearch, PACKAGING_TYPE_JAR, requestSpecificContexts);
	}

	public Collection<ArtifactInfo> getPluginArtifacts(Dependency artifactToSearch,
			IndexingContext... requestSpecificContexts) {
		return internalGetArtifacts(artifactToSearch, PACKAGING_TYPE_MAVEN_PLUGIN, requestSpecificContexts);
	}

	private Set<String> internalGetGroupIds(Dependency artifactToSearch, String packaging,
			IndexingContext... requestSpecificContexts) {
		final Query groupIdQ = indexer.constructQuery(MAVEN.GROUP_ID, artifactToSearch.getGroupId(), SearchType.SCORED);
		final Query jarPackagingQ = indexer.constructQuery(MAVEN.PACKAGING, packaging, SearchType.EXACT);
		final BooleanQuery query = new BooleanQuery.Builder().add(groupIdQ, Occur.MUST).add(jarPackagingQ, Occur.MUST)
				.build();
		List<IndexingContext> contexts = internalGetIndexingContexts(requestSpecificContexts);;
		final IteratorSearchRequest request = new IteratorSearchRequest(query, contexts, null);
		// TODO: Find the Count sweet spot
		request.setCount(7500);
		return createIndexerQuery(artifactToSearch, request).stream().map(ArtifactInfo::getGroupId)
				.collect(Collectors.toSet());
	}

	private List<IndexingContext> internalGetIndexingContexts(IndexingContext... requestSpecificContexts) {
		return (requestSpecificContexts != null && requestSpecificContexts.length > 0
				? Arrays.asList(requestSpecificContexts)
				: new LinkedList<>(indexingContexts.values())).stream().filter(Objects::nonNull)
						.collect(Collectors.toUnmodifiableList());
	}
	
	// TODO: Get groupid description for completion
	public Set<String> getGroupIds(Dependency artifactToSearch, IndexingContext... requestSpecificContexts) {
		return internalGetGroupIds(artifactToSearch, PACKAGING_TYPE_JAR, requestSpecificContexts);
	}

	public Set<String> getPluginGroupIds(Dependency artifactToSearch, IndexingContext... requestSpecificContexts) {
		return internalGetGroupIds(artifactToSearch, PACKAGING_TYPE_MAVEN_PLUGIN, requestSpecificContexts);
	}

	private CompletableFuture<Void> updateIndex(IndexingContext context) throws ComponentLookupException {
		if (context == null) {
			return CompletableFuture.runAsync(() -> {
				throw new IllegalArgumentException("context mustn't be null");
			});
		}
		if (isDisabledRepository(context.getId())) {
			return CompletableFuture.runAsync(() -> LOGGER.log(Level.INFO, "Central repository index disabled"));
		}
		LOGGER.log(Level.INFO, "Updating Index for " + context.getRepositoryUrl() + "...");
		Date contextCurrentTimestamp = context.getTimestamp();
		ResourceFetcher resourceFetcher = new WagonHelper.WagonFetcher(
				plexusContainer.lookup(Wagon.class, URI.create(context.getIndexUpdateUrl()).getScheme()),
				new AbstractTransferListener() {
					@Override
					public void transferStarted(TransferEvent transferEvent) {
						LOGGER.log(Level.INFO, "Downloading" + transferEvent.getResource().getName());
					}

					@Override
					public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length) {
					}

					@Override
					public void transferCompleted(TransferEvent transferEvent) {
						LOGGER.log(Level.INFO, "Done downloading " + transferEvent.getResource().getName());
					}
				}, null, null);

		IndexUpdateRequest updateRequest = new IndexUpdateRequest(context, resourceFetcher);
		return CompletableFuture.runAsync(() -> {
			try {
				IndexUpdateResult updateResult = indexUpdater.fetchAndUpdateIndex(updateRequest);
				if (updateResult.isSuccessful()) {
					LOGGER.log(Level.INFO, "Update successful for " + context.getRepositoryUrl());
					if (updateResult.isFullUpdate()) {
						LOGGER.log(Level.INFO, "Full update happened!");
					} else if (contextCurrentTimestamp.equals(updateResult.getTimestamp())) {
						LOGGER.log(Level.INFO, "No update needed, index is up to date!");
					} else {
						LOGGER.log(Level.INFO, "Incremental update happened, change covered " + contextCurrentTimestamp
								+ " - " + updateResult.getTimestamp() + " period.");
					}
				} else {
					LOGGER.log(Level.WARNING, "Index update failed for " + context.getRepositoryUrl());
				}
			} catch (IOException e) {
				// TODO: Fix this - the maven central context gets reported as broken when
				// another context is broken
				indexDownloadJobs.remove(context);
				CompletableFuture.runAsync(() -> {
					LOGGER.log(Level.SEVERE, e.getMessage(), e);
					throw new IllegalArgumentException(
							"Invalid Context: " + context.getRepositoryId() + " @ " + context.getRepositoryUrl());
				});

				// TODO: Maybe scan for maven metadata to use as an alternative to retrieve GAV
			}
		});
	}

	private IndexingContext initializeContext(URI repositoryURI) {
		String repositoryId = repositoryURI.toString();
		if (!isDisabledRepository(repositoryId)) {
			String fileSystemFriendlyName = repositoryURI.getHost() + repositoryURI.hashCode();
			File repositoryFile = new File(indexPath, fileSystemFriendlyName + "-cache");
			File indexDirectory = new File(indexPath, fileSystemFriendlyName + "-index");
			try {
				return indexer.createIndexingContext(repositoryId, repositoryId, repositoryFile, indexDirectory,
						repositoryId, null, true, true, indexers);
			} catch (Exception e) {
				LOGGER.log(Level.SEVERE, MessageFormat.format(
						"Error while creating indexing context with repository ID={0}, directory={1} in index directory={2}.",
						repositoryId, repositoryFile.getPath(), indexDirectory.getPath()), e);
			}
		} else {
			LOGGER.log(Level.INFO, "Central repository index disabled");
		}
		return null;
	}

	private boolean isDisabledRepository(String repositoryUri) {
		return (repositoryUri.equals("https://repo.maven.apache.org/maven2")
				|| repositoryUri.equals(CENTRAL_REPO.getId()) || repositoryUri.contains("maven_central"))
				&& disableCentralIndex;
	}
	
	public void closeContext() {
		for (IndexingContext context : indexingContexts.values()) {
			try {
				if (context != null) {
					indexer.closeIndexingContext(context, false);
					CompletableFuture<?> download = indexDownloadJobs.get(context);
					if (!download.isDone()) {
						download.cancel(true);
					}
				}
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Warning - could not close context: " + context.getId(), e);
			}
		}
		indexingContexts.clear();
		indexDownloadJobs.clear();
	}

	private List<ArtifactInfo> createIndexerQuery(Dependency artifactToSearch, final IteratorSearchRequest request) {
		IteratorSearchResponse response = null;
		try {
			response = indexer.searchIterator(request);
		} catch (IOException e) {
			LOGGER.log(Level.SEVERE, "Index search failed for " + String.join(":", artifactToSearch.getGroupId(),
					artifactToSearch.getArtifactId(), artifactToSearch.getVersion()), e);
		}
		List<ArtifactInfo> artifactInfos = new ArrayList<>();
		if (response != null) {
			response.getResults().forEach(artifactInfos::add);
		}
		return artifactInfos;
	}

}
