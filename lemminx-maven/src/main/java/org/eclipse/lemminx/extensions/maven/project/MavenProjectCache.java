/*******************************************************************************
 * Copyright (c) 2020, 2026 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.project;

import static org.eclipse.lemminx.extensions.maven.utils.URIUtils.toURIKey;
import static org.eclipse.lemminx.extensions.maven.utils.URIUtils.toURIString;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.ParseException;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.bridge.MavenRepositorySystem;
import org.apache.maven.cli.CLIManager;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelProblem;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblem.Severity;
import org.apache.maven.model.building.ModelProblem.Version;
import org.apache.maven.model.io.ModelParseException;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.DefaultProjectBuilder;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.extensions.maven.MavenLemminxExtension;
import org.eclipse.lemminx.extensions.maven.utils.DOMModelSource;
import org.eclipse.lemminx.services.IXMLDocumentProvider;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures.FutureCancelChecker;

public class MavenProjectCache {

	private static final String MVN_FOLDER = ".mvn";
	private static final String MAVEN_CONFIG = "maven.config";

	private static final Logger LOGGER = Logger.getLogger(MavenProjectCache.class.getName());
	private MavenLemminxExtension plugin;
	private final Map<String, LoadedMavenProjectProvider> projectCache;
	private final MavenSession mavenSession;
	private final IXMLDocumentProvider documentProvider;
	private ProjectBuildManager projectBuildManager;

	public MavenProjectCache(MavenLemminxExtension plugin, MavenSession mavenSession, IXMLDocumentProvider documentProvider) {
		this.plugin = plugin;
		this.mavenSession = mavenSession;
		this.projectCache = new HashMap<>();
		this.documentProvider = documentProvider;
		this.projectBuildManager = new ProjectBuildManager();
	}

	/**
	 * Should be called when Maven Lemminx Extension initialization 
	 * is successfully finished
	 */
	public void start() {
		projectBuildManager.start();
	}

	/**
	 * Should be called when Maven Lemminx Extension is set to shutdown
	 */
	public void stop() {
		projectBuildManager.stop();
	}
	
	class ProjectBuildManager {
		private static final int CORE_POOL_SIZE = 10;

		private Map<Object, BuildProjectRunnable> toProcess = new HashMap<>();
		private final PriorityBlockingQueue</*Runnable*/Runnable> runnables = new PriorityBlockingQueue<>(1, PRIORITIZED_DEEPEST_FIRST);
		private final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 1, 0, TimeUnit.MILLISECONDS, runnables);
		private final MavenXpp3Reader mavenReader = new MavenXpp3Reader();
		private ProjectBuilder projectBuilder;
		private MavenRepositorySystem repositorySystem;

		private final class BuildProjectRunnable implements Runnable {
			final String uri;
			final FileModelSource source;
			final CompletableFuture<LoadedMavenProject> future;
			private int priority;
			private File localTempRepository = null;

			private BuildProjectRunnable(String uri, FileModelSource source) {
				this.uri = uri;
				this.source = source;
				this.future = new CompletableFuture<>();
				this.priority = 0;
			}
			
			int getPriority() {
				return this.priority;
			}
			
			void bumpPriority() {
				this.priority++;
			}

			@Override
			public void run() {
				try {
					future.complete(build(source, new FutureCancelChecker(future)));
					
				} catch (Exception e) { // This should include CancellationException
					future.completeExceptionally(e);
				}
			}

			@Override
			public boolean equals(Object obj) {
				if (super.equals(obj)) {
					return true;
				}
				if (obj instanceof BuildProjectRunnable runnable) {
					return this.hashCode() == runnable.hashCode();
				}
				return false;
			}

			@Override
			public int hashCode() {
				return Objects.hash(toURIKey(uri), source);
			}

			public LoadedMavenProject build(FileModelSource source, CancelChecker cancelChecker) {
				Collection<ModelProblem> problems = new ArrayList<>();
				DependencyResolutionResult dependencyResolutionResult = null;
				MavenProject project = null;
				File file = source.getFile();
				try {
					ProjectBuildingRequest request = newProjectBuildingRequest(file, problems);

					ProjectBuildingResult buildResult = projectBuilder.build(source, request);
					cancelChecker.checkCanceled();
					project = buildResult.getProject();
					problems.addAll(buildResult.getProblems());
					dependencyResolutionResult = buildResult.getDependencyResolutionResult();

					if (project != null) {
						// setFile should ideally be invoked during project build, but related methods
						// to pass modelSource and pomFile are private
						project.setFile(file);
					}
				} catch (ProjectBuildingException e) {
					if (e.getResults() == null) {
						if (e.getCause() instanceof ModelBuildingException modelBuildingException) {
							// Try to manually build a minimal project from the document to collect
							// lower-level
							// errors and to have something usable in cache for most basic operations
							modelBuildingException.getProblems().stream()
									.filter(p -> !(p.getException() instanceof ModelParseException)).forEach(problems::add);
							try (InputStream documentStream = source.getInputStream()) {
								Model model = mavenReader.read(documentStream);
								cancelChecker.checkCanceled();
								project = new MavenProject(model);
								if (repositorySystem != null) {
									project.setRemoteArtifactRepositories(model.getRepositories().stream()
											.map(repo -> {
												try {
													return repositorySystem.buildArtifactRepositoryFromRepo(repo);
												} catch (InvalidRepositoryException ex) {
													LOGGER.log(Level.FINER, ex.getMessage(), ex);
													return null;
												}
											}).filter(Objects::nonNull).distinct().collect(Collectors.toList()));
								}
								project.setFile(file);
								project.setBuild(new Build());
							} catch (XmlPullParserException | EOFException parserException) {
								// XML document is invalid for parsing (eg user is typing), it's a valid state
								// that shouldn't log
								// exceptions
							} catch (IOException ex) {
								// Log at low severity for debugging purposes
								LOGGER.log(Level.FINER, e.getMessage(), e);
							}
						} else {
							problems.add(
									new DefaultModelProblem(e.getMessage(), Severity.FATAL, Version.BASE, null, -1, -1, e));
						}
					} else {
						e.getResults().stream().flatMap(result -> result.getProblems().stream()).forEach(problems::add);
						if (e.getResults().size() == 1) {
							project = e.getResults().get(0).getProject();
							if (project != null) {
								project.setFile(file);
							}
						}
					}
				} catch (CancellationException e) {
					// The document which has been used to load Maven project is out of dated
					throw e;
				} catch (Exception e) {
					// Do not add any info, like lastCheckedVersion or problems, to the cache
					// In case of project/problems etc. is not available due to an exception
					// happened.
					//
					LOGGER.log(Level.SEVERE, e.getMessage(), e);
					return null; // Nothing to be cached
				}

				if (project != null) {
					// Report to listeners registered in the extension
					if (localTempRepository == null) {
						localTempRepository = mavenSession.getRequest().getLocalRepositoryPath();
					}
					if (localTempRepository != null) {
						plugin.builtMavenProject(localTempRepository, project);
					}
				}
				
				return new LoadedMavenProject(project, problems, dependencyResolutionResult);
			}
		}

		private ProjectBuildManager() {
			initializeMavenBuildState();
		}

		private static final Object runnableKey(String uri, FileModelSource source) {
			return Integer.valueOf(Objects.hash(uri, source));
		}
		
		private void initializeMavenBuildState() {
			if (projectBuilder != null) {
				return;
			}
			try {
				projectBuilder = getPlexusContainer().lookup(ProjectBuilder.class);
				repositorySystem = getPlexusContainer().lookup(MavenRepositorySystem.class);
				System.setProperty(DefaultProjectBuilder.DISABLE_GLOBAL_MODEL_CACHE_SYSTEM_PROPERTY,
						Boolean.toString(true));
			} catch (ComponentLookupException e) {
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
			}
		}
		
		private void start() {
			if (executor.getCorePoolSize() == 0) {
				executor.setCorePoolSize(CORE_POOL_SIZE);
			}
		}

		private void stop() {
			if (executor.getCorePoolSize() > 0) {
				executor.setCorePoolSize(0);
			}
			executor.shutdown();
		}

		private static final Comparator<Runnable> PRIORITIZED_DEEPEST_FIRST = (o1, o2) -> {
			if (!(o1 instanceof BuildProjectRunnable r1 && o2 instanceof BuildProjectRunnable r2)) {
				return 0;
			}
			int result = Comparator.comparingInt(BuildProjectRunnable::getPriority)
					.compare(r1, r2);
			if (result == 0) {
				result = r1.uri.compareTo(r2.uri);
			}
			return result;
		};
		
		/**
		 * Asynchronously builds a provided document from a source provided
		 * 
		 * @param uri An URI String identifying the document ro be built
		 * @param source A FIleModelSource for the document to be built
		 * @return A CompletableFuture of LoadedMavenProject object
		 */
		public CompletableFuture<LoadedMavenProject> build(final String uri, final FileModelSource source) {
			BuildProjectRunnable runnable = null;
			Object key = runnableKey(toURIKey(uri), source);
			synchronized (toProcess) {
				runnable = toProcess.get(key);
				if (runnable != null) {
					// Project is already queued to be built, so just bump the 
					// runnable priority to force build to be started earlier.
					runnable.bumpPriority();
				} else {
					runnable = new BuildProjectRunnable(uri, source);
					toProcess.put(key, runnable);
					executor.execute(runnable);
					runnable.future.whenComplete((ok, error) -> toProcess.remove(key));
				}
			}
			return runnable.future;
		}

		/**
		 * Returns a cached MavenProject object or builds a new one from a file source
		 * The resulting MavenProject is a snapshot that is not to be cached (unless it's 
		 * taken from Maven Project Cache)
		 * 
		 * @param file of Maven Project to be built
		 * @return Optional of MavenProject object
		 */
		public Optional<MavenProject> getSnapshotProject(File file) {
			LoadedMavenProjectProvider projectProvider = projectCache.get(toURIKey(file));
			Integer last = projectProvider != null ? projectProvider.getLastCheckedVersion() : null;
			if (last != null && last.intValue() >= 0) {
				LoadedMavenProject loadedProject = projectProvider.getLoadedMavenProject().getNow(null);
				MavenProject project = loadedProject != null ? loadedProject.getMavenProject() : null;
				if (project != null) {
					return Optional.of(project);
				}
			}

			try {
				return Optional.of(projectBuilder.build(file, newProjectBuildingRequest(file, null)).getProject());
			} catch (ProjectBuildingException e) {
				List<ProjectBuildingResult> result = e.getResults();
				if (result != null && result.size() == 1 && result.get(0).getProject() != null) {
					return Optional.of(result.get(0).getProject());
				}
			} catch (Exception e) {
				// Some other kinds of exceptions may be thrown, for instance,
				// an IllegalStateException in case of fail to acquire write lock for an
				// artifact
				LOGGER.log(Level.SEVERE, e.getMessage(), e);
			}

			return Optional.empty();
		}

		/**
		 * Builds the MavenProject object from a Document being edited using a given Profile ID.
		 * The resulting MavenProject is a snapshot that is not to be cached
		 * 
		 * @param document Maven Project DOMDocument to be built
		 * @param profileId A profile ID to be used on builf
		 * @param resolve a boolean indicating if dependencies are to be resolved during the build
		 * @return Optional of MavenProject object
		 */
		public MavenProject getSnapshotProject(DOMDocument document, String profileId, boolean resolve) {
			// it would be nice to directly rebuild from Model instead of re-parsing text
			ProjectBuildingRequest request = newProjectBuildingRequest(resolve, getFileForDocument(document), null);
			if (profileId != null) {
				request.setActiveProfileIds(List.of(profileId));
			}
			try {
				DOMModelSource source = new DOMModelSource(document);
				return projectBuilder.build(source, request).getProject();
			} catch (CancellationException e) {
				// The document which has been used to load Maven project is out of dated
				throw e;
			} catch (ProjectBuildingException e) {
				List<ProjectBuildingResult> result = e.getResults();
				if (result != null && result.size() == 1 && result.get(0).getProject() != null) {
					return result.get(0).getProject();
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}

		/**
		 * Creates a new default Maven Project Building request (with dependency resolve
		 * enabled)
		 * 
		 * @param projectFile       the project file or base directory of the building
		 *                          request
		 * @param problemsCollector if not <code>null</code> this method will add
		 *                          problems to the collection if there is a problem
		 *                          while creating the request
		 * 
		 * @return A ProjectBuildingRequest object
		 */
		public ProjectBuildingRequest newProjectBuildingRequest(File projectFile,
				Collection<ModelProblem> problemsCollector) {
			return newProjectBuildingRequest(true, projectFile, problemsCollector);
		}
		
		/**
		 * Creates a new default Maven Project Building request
		 * 
		 * @param resolveDependencies a boolean indicating if the dependency resolve is
		 *                            to be enabled on the request.
		 * @param projectFile         the project file or base directory of the building
		 *                            request
		 * @param problemsCollector   if not <code>null</code> this method will add
		 *                            problems to the collection if there is a problem
		 *                            while creating the request
		 * 
		 * @return A ProjectBuildingRequest object
		 */
		public ProjectBuildingRequest newProjectBuildingRequest(boolean resolveDependencies, File projectFile,
				Collection<ModelProblem> problemsCollector) {
			ProjectBuildingRequest request = new DefaultProjectBuildingRequest();
			MavenExecutionRequest mavenRequest = mavenSession.getRequest();
			request.setSystemProperties(mavenRequest.getSystemProperties());
			request.setLocalRepository(mavenRequest.getLocalRepository());
			request.setRemoteRepositories(mavenRequest.getRemoteRepositories());
			request.setPluginArtifactRepositories(mavenRequest.getPluginArtifactRepositories());
			// TODO more to transfer from mavenRequest to ProjectBuildingRequest?
			request.setRepositorySession(mavenSession.getRepositorySession());
			request.setResolveDependencies(resolveDependencies);

			// See: https://issues.apache.org/jira/browse/MRESOLVER-374
			Properties userProperties = request.getUserProperties();
			userProperties.setProperty("aether.syncContext.named.factory", "noop");
			File multiModuleProjectDirectory = computeMultiModuleProjectDirectory(projectFile);
			if (multiModuleProjectDirectory != null) {
				File mavenConfig = new File(multiModuleProjectDirectory, MVN_FOLDER + "/" + MAVEN_CONFIG);
				if (mavenConfig.isFile()) {
					try {
						CLIManager manager = new CLIManager();
						String[] args;
						try (Stream<String> lines = Files.lines(mavenConfig.toPath(), Charset.defaultCharset())) {
							args = lines.filter(arg -> !arg.isEmpty()).toArray(String[]::new);
						}
						CommandLine commandline = manager.parse(args);
						if (commandline.hasOption(CLIManager.SET_USER_PROPERTY)) {
							String[] configUserProperties = commandline.getOptionValues(CLIManager.SET_USER_PROPERTY);
							if (configUserProperties != null) {
								for (String property : configUserProperties) {
									int index = property.indexOf('=');
									if (index <= 0) {
										userProperties.setProperty(property.trim(), "true");
									} else {
										userProperties.setProperty(property.substring(0, index).trim(),
												property.substring(index + 1).trim());
									}
								}
							}
						}
						if (commandline.hasOption(CLIManager.ACTIVATE_PROFILES)) {
							String[] profileOptionValues = commandline.getOptionValues(CLIManager.ACTIVATE_PROFILES);
							if (profileOptionValues != null) {
								for (String profileOptionValue : profileOptionValues) {
									StringTokenizer tokenizer = new StringTokenizer(profileOptionValue, ",");
									while (tokenizer.hasMoreTokens()) {
										String profileToken = tokenizer.nextToken().trim();
										if (profileToken.startsWith("-") || profileToken.startsWith("!")) {
											request.getInactiveProfileIds().add(profileToken.substring(1));
										} else if (profileToken.startsWith("+")) {
											request.getActiveProfileIds().add(profileToken.substring(1));
										} else {
											request.getActiveProfileIds().add(profileToken);
										}
									}
								}
							}
						}
					} catch (IOException | ParseException e) {
						if (problemsCollector != null) {
							problemsCollector.add(new DefaultModelProblem("Problem parsing " + mavenConfig,
									ModelProblem.Severity.ERROR, null, null,
									-1, -1, e));
						}
					}
				}
			}
			return request;
		}
	}

	/**
	 * Returns the PlexusContainer object used in Maven Session
	 * 
	 * @return A PlexusComtainer object
	 */
	public PlexusContainer getPlexusContainer() {
		return mavenSession.getContainer();
	}

	/**
	 * Returns the list of built Maven Projects currently available in the 
	 * Maven Project Cache
	 * 
	 * @return A Collection of currently available Maven Projects
	 */
	public Collection<MavenProject> getProjects() {
		return projectCache.values().stream()
				.map(LoadedMavenProjectProvider::getLoadedMavenProject)
				.map(f -> f.getNow(null)).filter(Objects::nonNull)
				.map(LoadedMavenProject::getMavenProject)
				.toList();
	}

	/**
	 * Returns the last successfully parsed and cached Maven Project for the
	 * given POM file if exists or builds a Snapshot Maven Project (not caching 
	 * the Maven Project build results, nor saving the build problems if any)
	 * 
	 * @param file
	 * @return Optional Maven Project
	 */
	public Optional<MavenProject> getSnapshotProject(File file) {
		return projectBuildManager.getSnapshotProject(file);
	}
	
	/**
	 * Returns the successfully parsed Maven Project built from the given 
	 * document (not caching the Maven Project build results, nor saving 
	 * the build problems if any)
	 * 
	 * @param document
	 * @param profileId 
	 * @return Optional Maven Project
	 */
	public MavenProject getSnapshotProject(DOMDocument document, String profileId) {
		return getSnapshotProject(document, profileId, true);
	}

	/**
	 * Returns the successfully parsed Maven Project built from the given 
	 * document (not caching the Maven Project build results, nor saving 
	 * the build problems if any)
	 * 
	 * @param document
	 * @param profileId 
	 * @param resolve
	 * @return Optional Maven Project
	 */
	public MavenProject getSnapshotProject(DOMDocument document, String profileId, boolean resolve) {
		return projectBuildManager.getSnapshotProject(document, profileId, resolve);
	}

	/**
	 * Returns the last successfully parsed and cached Maven Project for the 
	 * given POM file if exists and up to date
	 * 
	 * @param pomFile A given POM File
	 * @return the last MavenDocument that could be build for the more recent
	 *         version of the provided document. If document fails to build a
	 *         MavenProject, a former version will be returned. Can be
	 *         <code>null</code>.
	 */
	public MavenProject getLastSuccessfulMavenProject(File pomFile) {
		CompletableFuture<LoadedMavenProject> project = getLoadedMavenProject(pomFile);
		try {
			return project != null ? project.get().getMavenProject() : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		return null;
	}
	
	/**
	 * Returns the last successfully parsed and cached Maven Project for the given
	 * document
	 * 
	 * @param document A given Document
	 * @return the last MavenDocument that could be build for the more recent
	 *         version of the provided document. If document fails to build a
	 *         MavenProject, a former version will be returned. Can be
	 *         <code>null</code>.
	 */
	public MavenProject getLastSuccessfulMavenProject(DOMDocument document) {
		CompletableFuture<LoadedMavenProject> project = getLoadedMavenProject(document);
		try {
			return project != null ? project.get().getMavenProject() : null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException e) {
			LOGGER.log(Level.SEVERE, e.getMessage(), e);
		}
		return null;
	}

	/**
	 * Returns a Completable Future of Loaded Maven Project for the given
	 * file
	 * 
	 * @param file A POM file
	 * @return Completable Future of LoadedMavenDocument that could be build for 
	 * 		the most recent version of the provided file.
	 */
	public CompletableFuture<LoadedMavenProject> getLoadedMavenProject(File pomFile) {
		return getLoadedMavenProject(toURIString(pomFile));
	}
	
	/**
	 * Returns a Completable Future of Loaded Maven Project for the given
	 * document
	 * 
	 * @param file A DOMDocument 
	 * @return Completable Future of LoadedMavenDocument that could be build for 
	 * 		the most recent version of the provided document.
	 */
	public CompletableFuture<LoadedMavenProject> getLoadedMavenProject(DOMDocument document) {
		return getLoadedMavenProject(document.getDocumentURI());
	}
	
	/**
	 * Returns a Completable Future of Loaded Maven Project for the given
	 * URI String document identifier 
	 * 
	 * @param uriString A document URI String
	 * @return Completable Future of LoadedMavenDocument that could be build for 
	 * 		the most recent version of the document represented by the specified 
	 * 		URI String identifier.
	 */
	public CompletableFuture<LoadedMavenProject> getLoadedMavenProject(String uriString) {
		String uriKey = toURIKey(uriString);
		LoadedMavenProjectProvider provider = projectCache.get(uriKey);
		if (provider == null) {
			synchronized (projectCache) {
				provider = projectCache.get(uriKey);
				if (provider == null) {
					provider = new LoadedMavenProjectProvider(uriString, documentProvider, projectBuildManager);
					projectCache.put(uriKey, provider);
				}
			}
		}
		return provider.getLoadedMavenProject();
	}

	/**
	 * @param file a base file or directory, may be <code>null</code>
	 * @return the value for `maven.multiModuleProjectDirectory` as defined in Maven
	 *         launcher
	 */
	public static File computeMultiModuleProjectDirectory(File file) {
		if (file == null) {
			return null;
		}
		final File basedir = file.isDirectory() ? file : file.getParentFile();
		for (File root = basedir; root != null; root = root.getParentFile()) {
			if (new File(root, MVN_FOLDER).isDirectory()) {
				return root;
			}
		}
		return null;
	}

	private static File getFileForDocument(DOMDocument document) {
		String documentURI = document.getDocumentURI();
		if (documentURI == null) {
			return null;
		}
		if (documentURI.toLowerCase().startsWith("file:")) {
			try {
				return new File(new URI(documentURI));
			} catch (URISyntaxException e) {
				return null;
			}
		}
		return new File(documentURI);
	}

}
