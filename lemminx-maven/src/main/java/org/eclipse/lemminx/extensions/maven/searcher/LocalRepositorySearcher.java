/*******************************************************************************
 * Copyright (c) 2019, 2026 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.searcher;

import static org.eclipse.lemminx.extensions.maven.utils.ParticipantUtils.isWellDefinedDependency;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.lemminx.commons.progress.ProgressMonitor;
import org.eclipse.lemminx.commons.progress.ProgressSupport;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.NoopProgressSupport;
import org.eclipse.lemminx.extensions.maven.project.IMavenProjectBuildListener;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;

/**
 * Search for collecting artifacts from the local repository /.m2
 *
 */
public class LocalRepositorySearcher implements IMavenProjectBuildListener {

	private static final Logger LOGGER = Logger.getLogger(LocalRepositorySearcher.class.getName());
	private static long REPOSITORY_UPDATE_PERIOD = 30*60*1000; // 30 minutes
	
	private final ProgressSupport progressSupport;
	private Map<File, Cache> cache = new HashMap<>();
	private Thread updaterThread;

	class Cache {
		private File repository;
		private Map<Path, Artifact> artifacts;
		private CompletableFuture<Collection<Artifact>> future;
		private boolean updateRequested = false;
		
		Cache (File repository) {
			this.repository = repository;
			this.artifacts = new HashMap<>();
		}
		
		public File getRepository() {
			return repository;
		}

		public void cancel() {
			if (future != null) {
				try {
					future.cancel(true);
				} catch (CancellationException e) {
					// Ignore
					e.printStackTrace();
				}
				future = null;
			}
		}

		CompletableFuture<Collection<Artifact>> getArtifacts() {
			synchronized (this) {
				if (future == null || future.isCompletedExceptionally()) {
					future = repository == null 
							? CompletableFuture.completedFuture(artifacts.values())
							: CompletableFutures.computeAsync(cancelChecker -> doUpdate(true, cancelChecker));
				}
			}
			return future;
		}
		
		public void updateBuiltArtifact(Artifact artifact) {
			if (artifact != null) {
				Path groupPath = new File(repository, artifact.getGroupId().replace('.', File.separatorChar)).toPath();
				Path artifactPath = groupPath.resolve(artifact.getArtifactId());
				Path versionPath = artifactPath.resolve(artifact.getVersion());
				
				Artifact probe = probeDirectoryForArtifact(versionPath, () -> {});
				if (probe != null) {
					synchronized (this) {
						artifacts.put(artifactPath, probe);
					}
				}
			}			
		}
		
		private Collection<Artifact> doUpdate(boolean initial, CancelChecker cancelChecker) {
			UpdaterProgressMonitor pm = new UpdaterProgressMonitor(true);
			try {
				pm.begin();
				Collection<Path> toRemove = artifacts.keySet().stream()
						.collect(Collectors.toList()); 
				pm.incrementTotal(toRemove.size());
				updateArtifacts(repository.toPath(), toRemove, pm, cancelChecker);
				for (Path path : toRemove) {
					pm.report(path.getFileName().toString());
					synchronized (this) {
						artifacts.remove(path);
					}
				}
			} finally {
				pm.end();
			}
			return artifacts.values();
		}
		
		private void updateArtifacts() {
			synchronized (this) {
				if (future == null || future.isDone()) {
					// Replace the existing completed future
					LOGGER.info("Starting local repository cache update for ''" + repository + "''...");
					updateRequested = false;
					future = repository == null 
							? CompletableFuture.completedFuture(artifacts.values())
							: CompletableFutures.computeAsync(cancelChecker -> doUpdate(true, cancelChecker));
					future.whenComplete((ok, error) -> {
						if (error != null && !(error instanceof CancellationException)) {
							LOGGER.log(Level.SEVERE, "Local repository cache update failed for : ''" + repository + "'': " + error.getMessage(),  error);
						}
						LOGGER.info("Finished local repository cache update for ''" + repository + "''...");
					});
				} else {
					if (!updateRequested) {
						// Re-schedule update after the current future is completed
						LOGGER.info("Re-scheduling local repository cache update for ''" + repository + "''...");
						updateRequested = true;
						future.whenComplete((ok, error) -> {
							updateArtifacts();
						});
					} else {
						LOGGER.info("Skipping local repository cache update  for ''" + repository + "'' - already scheduled");
					}
				}
			}
		}
		
		class UpdaterProgressMonitor {
			private ProgressMonitor monitor;
			private int total = 0;
			private int completed = 0;
			private int lastReportedPercentage = -1;
			private boolean initial;
			
			public UpdaterProgressMonitor(boolean initial) {
				this.initial = initial;
			}
			
			int incrementTotal(int delta) {
				this.total += delta;
				return this.total;
			}
			
			int incrementCompleted(int delta) {
				this.completed += delta;
				return this.completed;
			}
			
			Integer percentage() {
				if (total == 0 || completed >total) {
					return 100;
				}
				return 100 * completed / total;
			}
			
			void begin() {
				monitor = Optional.ofNullable(progressSupport.createProgressMonitor()).orElse(new NoopProgressSupport().createProgressMonitor());
				monitor.begin((initial ? "Loading" : "Updating")
								+ " local artifacts from ''" + repository.toPath() + "''...",
						null, 100, null);
            }
			
			void report(String entry) {
				var newCoompleted = incrementCompleted(1);
				// Limiting report counts to 10 (one after each 10%-progress)
				int percentage = percentage();
				if (lastReportedPercentage < 0 || percentage >= 100
						|| (percentage / 10 - lastReportedPercentage / 10) % 10 >= 1) {
					monitor.report("Scanning folder ''" + entry + "'' (" + newCoompleted + " / "
							+ total + ")...", percentage(), null);
					lastReportedPercentage = percentage;
				}
            }

			void end() {
				monitor.end("Finished loading local artifacts from ''" + repository.toPath() + "''.");
			}
		}
		
		private Collection<Artifact> updateArtifacts(Path dir, Collection<Path> oldPaths, UpdaterProgressMonitor progressMonitor, CancelChecker cancelChecker) {
			List<Path> subPaths = getSubDirectories(dir);
			progressMonitor.incrementTotal(subPaths.size());
			Artifact latestArtifact = null;
			ArtifactVersion latestVersion = null;
			for (Path entry : subPaths) {
				progressMonitor.report(entry.getFileName().toString());
				if (Files.isDirectory(entry)) {
					if (oldPaths.remove(dir)) {
						progressMonitor.incrementTotal(-1);
					}
					Artifact artifact = probeDirectoryForArtifact(entry, cancelChecker);
					if (artifact != null) {
						ArtifactVersion version = new DefaultArtifactVersion(artifact.getVersion());
						if (latestArtifact == null || latestVersion.compareTo(version) < 0) {
							latestArtifact = artifact;
							latestVersion = version;
						}
					}
					updateArtifacts(entry, oldPaths, progressMonitor, cancelChecker);
				}
			}
			if (latestArtifact != null) {
				// Add or replace the existing artifact if the version is newer
				Artifact outdatedArtifact = artifacts.get(dir);
				if (outdatedArtifact == null 
						|| latestVersion.compareTo(new DefaultArtifactVersion(
								outdatedArtifact.getVersion())) > 0) {
					synchronized (this) {
						artifacts.put(dir, latestArtifact);
					}  
				}
			} else {
				// Remove outdated artifact 
				Artifact outdatedArtifact = artifacts.get(dir);
				if (outdatedArtifact != null) {
					synchronized (this) {
						artifacts.remove(dir);
					}  
				}
			}
			return artifacts.values();
		}

		private Artifact probeDirectoryForArtifact(Path dir, CancelChecker cancelChecker) {
			if (dir.getFileName().toString().charAt(0) == '.') {
				cancelChecker.checkCanceled();
				return null;
			}
			if (!Character.isDigit(dir.getFileName().toString().charAt(0))) {
				cancelChecker.checkCanceled();
				return null;
			}
			Path artifactFolderPath = repository.toPath().relativize(dir);
			if (artifactFolderPath.getNameCount() < 3) {
				cancelChecker.checkCanceled();
				// eg "maven-dependency-plugin/3.1.2"
				return null;
			}
			ArtifactVersion version = new DefaultArtifactVersion(artifactFolderPath.getFileName().toString());
			String artifactId = artifactFolderPath.getParent().getFileName().toString();
			String groupId = artifactFolderPath.getParent().getParent().toString()
					.replace(artifactFolderPath.getFileSystem().getSeparator(), ".");
			if (!new File(dir.toFile(), artifactId + '-' + version.toString() + ".pom").isFile()) {
				cancelChecker.checkCanceled();
				return null;
			}
			cancelChecker.checkCanceled();
			return new DefaultArtifact(groupId, artifactId, null, version.toString());
		}
		
		private static List<Path> getSubDirectories(Path directoryPath) {
			List<Path> subDirectories = new ArrayList<>();
			try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(directoryPath)) {
				for (Path entry : directoryStream) {
					if (Files.isDirectory(entry)) {
						subDirectories.add(entry);
					}
				}
			} catch (IOException e) {
				// Do nothing
			}
			return subDirectories;
		}
	}
	
	public LocalRepositorySearcher(Set<File> localRepositoryDirs, ProgressSupport progressSupport) {
		this.progressSupport = Objects.requireNonNull(progressSupport);
		// Force the load of the local artifacts done in background
		localRepositoryDirs.stream().filter(Objects::nonNull)
			.forEach(this::createLocalLocalRepositoryCache);
		this.updaterThread = new Thread(() -> {
			try {
				LOGGER.log(Level.INFO, "Local repo updater started");
				while (true) {
					Thread.sleep(REPOSITORY_UPDATE_PERIOD);
					try {
						LocalRepositorySearcher.this.updateArtifacts();
					} catch (CancellationException e) {
						// Ignore
					}
				}
			} catch (InterruptedException e) {
				LOGGER.log(Level.INFO, "Local repo updater stopped");
			}
		});
		updaterThread.start();
	}

	public Set<String> searchGroupIds() throws IOException {
		return getLocalArtifactsLastVersion().stream().map(Artifact::getGroupId).distinct().collect(Collectors.toSet());
	}

	public Set<String> searchPluginGroupIds() throws IOException {
		return getLocalPluginArtifacts().stream().map(Artifact::getGroupId).distinct().collect(Collectors.toSet());
	}

	public Collection<Artifact> getLocalPluginArtifacts() {
		return getLocalArtifactsLastVersion().stream().filter(gav -> gav.getArtifactId().contains("-plugin"))
				.collect(Collectors.toSet());
	}

	/**
	 * Returns the local artifacts (with last version) from the all local
	 * repository.
	 * 
	 * @return the local artifacts (with last version) from the all local
	 *         repository.
	 */
	public Collection<Artifact> getLocalArtifactsLastVersion() {
		return allOf(cache.values().stream().map(Cache::getArtifacts).toList())
				.getNow(Collections.emptyList());
	}

	private static <T> CompletableFuture<Collection<T>> allOf(Collection<CompletableFuture<Collection<T>>> futures) {
		return CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()])) //
				.thenApply(__ -> futures.stream() //
						.map(future -> future.getNow(null))
						.filter(Objects::nonNull)
						.flatMap(list -> list.stream())
						.toList());
	}

	private void createLocalLocalRepositoryCache(File localRepository) {
		Cache repositoryCcache = cache.get(localRepository);
		if (repositoryCcache == null) {
			synchronized (cache) {
				repositoryCcache = cache.get(localRepository);
				if (repositoryCcache == null) {
					repositoryCcache = new Cache(localRepository);
					cache.put(localRepository, repositoryCcache);
				}
			}
		}
		
		CompletableFuture<Collection<Artifact>> future = repositoryCcache.getArtifacts();
		if (MavenLemminxExtension.isUnitTestMode()) {
			try {
				future.get();
			} catch (InterruptedException | ExecutionException e) {
				LOGGER.log(Level.SEVERE, "Local repository cache creation failed for ''"
						+ localRepository + "''", e);
			}
		}
	}

	// TODO consider using directly ArtifactRepository for those 2 methods
	public File findLocalFile(Dependency dependency) {
		if (isWellDefinedDependency(dependency)) {
			File artifactFile = null;
			for (File localRepository : cache.keySet()) {
				artifactFile = new File(localRepository, dependency.getGroupId().replace('.', File.separatorChar)
						+ File.separatorChar + dependency.getArtifactId() + File.separatorChar + dependency.getVersion()
						+ File.separatorChar + dependency.getArtifactId() + '-' + dependency.getVersion() + ".pom");
				if (artifactFile.isFile()) {
					return artifactFile;
				}
			}
			return artifactFile;
		}
		return null;
	}

	public File findLocalFile(Artifact gav) {
		File artifactFile = null;
		for (File localRepository : cache.keySet()) {
			artifactFile = new File(localRepository,
					gav.getGroupId().replace('.', File.separatorChar) + File.separatorChar + gav.getArtifactId()
							+ File.separatorChar + gav.getVersion() + File.separatorChar + gav.getArtifactId() + '-'
							+ gav.getVersion() + ".pom");
			if (artifactFile.isFile()) {
				return artifactFile;
			}
		}
		return artifactFile;
	}

	public void stop() {
		// Stop the thread which collects local repository artifacts
		if (updaterThread != null) {
			updaterThread.interrupt();
			updaterThread = null;
		}
		synchronized (cache) {
			try {
				cache.values().forEach(Cache::cancel);
				cache.clear();
			} catch (CancellationException e) {
				// Ignore
			}
		}
	}

	public void updateArtifacts() {
		cache.keySet().stream().forEach(repository -> {
			Cache repositoryCache = cache.get(repository);
			if (repositoryCache != null) {
				repositoryCache.updateArtifacts();
			}
		});
	}

	@Override
	public void builtMavenProject(File repository, MavenProject mavenProject) {
		Artifact artifact = toArtifact(mavenProject.getArtifact());
		if (artifact == null) {
			return;
		}
		
		synchronized (cache) {
			Cache repositoryCache = cache.get(repository);
			if (repositoryCache != null) {
				repositoryCache.updateBuiltArtifact(artifact);
				mavenProject.getArtifacts().stream()
					.map(this::toArtifact)
					.filter(Objects::nonNull)
					.forEach(repositoryCache::updateBuiltArtifact);
			}
		}
	}
	
	private Artifact toArtifact(org.apache.maven.artifact.Artifact mavenArtifact) {
		Artifact artifact = new DefaultArtifact(
				mavenArtifact.getGroupId(), 
				mavenArtifact.getArtifactId(), 
				null,
				mavenArtifact.getVersion());
		artifact.setFile(mavenArtifact.getFile());
		return artifact;
	}
}
